package net.hycrafthd.youtube_source_services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * A local HTTP service running in its own Deno process.
 * <p>
 * Port and token are chosen once per instance, so the URL handed to youtube-source stays valid when the process is
 * restarted after a crash. The service only listens on 127.0.0.1 and rejects requests without the token.
 */
public abstract class DenoService implements AutoCloseable {

    private static final int MAX_RESTARTS = 5;
    private static final long STABLE_UPTIME_MS = TimeUnit.MINUTES.toMillis(1);

    protected final DenoRuntime runtime;
    protected final Path dir;
    protected final Consumer<String> log;
    private final String name;
    private final int port;
    private final String token;

    private Process process;
    private boolean stopRequested;
    private boolean restartOnCrash = true;
    private long startTimeoutSeconds = 300;
    private int failedRestarts;
    private long startedAt;

    protected DenoService(DenoRuntime runtime, String name, Consumer<String> log) throws IOException {
        this.runtime = runtime;
        this.name = name;
        this.dir = runtime.getBaseDir().resolve(name);
        this.log = log;

        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }

        byte[] tokenBytes = new byte[24];
        new SecureRandom().nextBytes(tokenBytes);
        this.token = FileUtils.toHex(tokenBytes);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, name + "-shutdown"));
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return "http://127.0.0.1:" + port;
    }

    public int getPort() {
        return port;
    }

    /** Value for the {@code Authorization} header, passed to youtube-source as the service password. */
    public String getToken() {
        return token;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /** Whether the process is started again when it exits without {@link #stop()}. Enabled by default. */
    public synchronized void setRestartOnCrash(boolean restartOnCrash) {
        this.restartOnCrash = restartOnCrash;
    }

    /**
     * How long {@link #start()} waits for the service to become ready, 300 seconds by default. Downloads are not
     * included, but loading many npm files can take long on slow disks or with an antivirus scanning each file.
     */
    public synchronized void setStartTimeoutSeconds(long startTimeoutSeconds) {
        this.startTimeoutSeconds = startTimeoutSeconds;
    }

    /**
     * Starts the service on a background thread. The first start downloads Deno and the service, so never wait for
     * this on the Minecraft render thread.
     */
    public CompletableFuture<Void> startAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                start();
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }, name + "-start");
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    /** Installs the service if needed, starts it and blocks until it accepts requests. Does nothing if it runs already. */
    public synchronized void start() throws IOException, InterruptedException {
        stopRequested = false;

        if (isRunning()) {
            return;
        }

        Path deno = runtime.getExecutable();
        Path app = install();

        List<String> command = new ArrayList<>();
        command.add(deno.toString());
        command.add("run");
        command.addAll(arguments(app));

        ProcessBuilder builder = new ProcessBuilder(command).directory(app.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("HOST", "127.0.0.1");
        environment.put("PORT", Integer.toString(port));
        environment.put("API_TOKEN", token);
        environment.put("DENO_DIR", runtime.getDenoDir().toString());
        environment.put("DENO_NO_UPDATE_CHECK", "1");
        environment.put("NO_COLOR", "1");
        environment.putAll(environment(app));

        long start = System.nanoTime();
        Process started = builder.start();
        CompletableFuture<Void> ready = new CompletableFuture<>();

        // The output must be drained continuously, otherwise the child blocks once the pipe buffer is full
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    log.accept("[" + name + "] " + line);
                    if (line.contains(readyMarker())) {
                        ready.complete(null);
                    }
                }
            } catch (IOException ignored) {
            }
            // The output closes when the process ends
            ready.completeExceptionally(new IOException(name + " exited before it was ready"));
            onProcessExit(started);
        }, name + "-output");
        reader.setDaemon(true);
        reader.start();

        try {
            // A first start downloads npm/jsr modules into DENO_DIR, later starts are fast
            ready.get(startTimeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            destroy(started);
            throw new IOException(name + " failed to start", e instanceof ExecutionException ? e.getCause() : e);
        } catch (InterruptedException e) {
            destroy(started);
            throw e;
        }

        process = started;
        startedAt = System.currentTimeMillis();
        afterStart();
        log.accept("[" + name + "] ready on " + getUrl() + " after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
    }

    public synchronized void stop() {
        stopRequested = true;
        if (process != null) {
            destroy(process);
            process = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void onProcessExit(Process exited) {
        int delay;

        synchronized (this) {
            // Exits during start, after stop() or of an already replaced process are handled elsewhere
            if (process != exited || stopRequested) {
                return;
            }
            process = null;

            log.accept("[" + name + "] exited unexpectedly with code " + exitCode(exited));
            if (!restartOnCrash) {
                return;
            }
            if (System.currentTimeMillis() - startedAt > STABLE_UPTIME_MS) {
                failedRestarts = 0;
            }
            delay = nextRestartDelay();
        }

        if (delay >= 0) {
            scheduleRestart(delay);
        }
    }

    /** @return the delay before the next restart attempt in ms, or -1 after too many failed attempts */
    private int nextRestartDelay() {
        failedRestarts++;
        if (failedRestarts > MAX_RESTARTS) {
            log.accept("[" + name + "] giving up after " + MAX_RESTARTS + " restarts");
            return -1;
        }
        return failedRestarts * 2000;
    }

    private void scheduleRestart(int delayMs) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                synchronized (this) {
                    if (stopRequested || isRunning()) {
                        return;
                    }
                }
                log.accept("[" + name + "] restarting");
                start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.accept("[" + name + "] restart failed: " + e);
                int next;
                synchronized (this) {
                    next = stopRequested ? -1 : nextRestartDelay();
                }
                if (next >= 0) {
                    scheduleRestart(next);
                }
            }
        }, name + "-restart");
        thread.setDaemon(true);
        thread.start();
    }

    private static int exitCode(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** Marks an installation as complete, so an interrupted install is redone on the next start. */
    protected static boolean isInstalled(Path app) {
        return Files.exists(app.resolve(".installed"));
    }

    protected static void markInstalled(Path app) throws IOException {
        Files.createFile(app.resolve(".installed"));
    }

    /** Downloads and prepares the service if needed and returns its working directory. */
    protected abstract Path install() throws IOException, InterruptedException;

    /** Everything after {@code deno run}. */
    protected abstract List<String> arguments(Path app) throws IOException;

    /** Additional environment variables. HOST, PORT, API_TOKEN and DENO_DIR are already set. */
    protected abstract Map<String, String> environment(Path app) throws IOException;

    /** A line printed by the service once it accepts requests. */
    protected abstract String readyMarker();

    /** Runs after every successful start, before {@link #start()} returns. */
    protected void afterStart() {
    }
}
