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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A local HTTP service running in its own Deno process.
 * <p>
 * Port and token are chosen once per instance, so the URL handed to youtube-source stays valid when the process is
 * stopped and started again or restarted after a crash. The service only listens on 127.0.0.1 and rejects requests
 * without the token.
 * <p>
 * Starts, crash handling and restarts all run one after another on a single thread per service, so they never race
 * each other. {@link #stop()} is the only thing that acts from outside that thread: it kills the process directly, so
 * it neither waits for nor is overtaken by a start that is still running.
 */
abstract class DenoService implements LocalService {

    private static final long STABLE_UPTIME_MS = TimeUnit.MINUTES.toMillis(1);

    protected final DenoRuntime runtime;
    protected final Path dir;
    protected final Consumer<String> log;
    private final String name;
    private final int port;
    private final String token;
    private final long startTimeoutSeconds;
    private final int maxRestarts;
    private final ScheduledExecutorService executor;

    /** The launched process, while it is starting and while it runs. Cleared by whoever ends it first. */
    private final AtomicReference<Process> process = new AtomicReference<>();
    /** Counts calls to {@link #stop()}; starts and restarts requested before a stop see the change and give up. */
    private final AtomicInteger stops = new AtomicInteger();
    /** The process that finished starting; {@link #isRunning()} is only true while this is the launched one. */
    private volatile Process ready;

    // Only used on the executor thread
    private int failedRestarts;
    private long startedAt;

    /** Uses the start timeout and restart limit from {@code services.properties}. */
    protected DenoService(DenoRuntime runtime, String name, Consumer<String> log) throws IOException {
        this(runtime, name, log, Config.get().getInt("start-timeout-seconds"), Config.get().getInt("restart.max-attempts"));
    }

    protected DenoService(DenoRuntime runtime, String name, Consumer<String> log, long startTimeoutSeconds, int maxRestarts) throws IOException {
        this.runtime = runtime;
        this.name = name;
        this.dir = runtime.getBaseDir().resolve(name);
        this.log = log;
        this.startTimeoutSeconds = startTimeoutSeconds;
        this.maxRestarts = maxRestarts;

        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }

        byte[] tokenBytes = new byte[24];
        new SecureRandom().nextBytes(tokenBytes);
        this.token = FileUtils.toHex(tokenBytes);

        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    String getName() {
        return name;
    }

    @Override
    public String getUrl() {
        return "http://127.0.0.1:" + port;
    }

    @Override
    public String getToken() {
        return token;
    }

    @Override
    public boolean isRunning() {
        Process current = process.get();
        return current != null && current == ready && current.isAlive();
    }

    /**
     * Installs the service if needed and starts it in the background. Completes once it accepts requests, right away
     * if it runs already, and exceptionally if the start fails or {@link #stop()} is called first.
     */
    CompletableFuture<Void> start() {
        int stopsAtRequest = stops.get();
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                if (stops.get() != stopsAtRequest) {
                    throw new CancellationException(name + " was stopped before it started");
                }
                failedRestarts = 0;
                if (!isRunning()) {
                    launch(stopsAtRequest);
                }
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Stops the process, including one that is still starting, and cancels pending restarts. It can be started again. */
    void stop() {
        stops.incrementAndGet();
        Process current = process.getAndSet(null);
        if (current != null) {
            destroy(current);
        }
    }

    /** Runs on the executor. */
    private void launch(int stopsAtRequest) throws IOException, InterruptedException {
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
        process.set(started);
        // Checked after publishing the process: either stop() sees and kills it, or this sees the stop
        if (stops.get() != stopsAtRequest) {
            abandon(started);
            throw new CancellationException(name + " was stopped while starting");
        }

        CompletableFuture<Void> readyFuture = new CompletableFuture<>();
        String readyMarker = readyMarker();

        // The output must be drained continuously, otherwise the child blocks once the pipe buffer is full
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    log.accept("[" + name + "] " + line);
                    if (line.contains(readyMarker)) {
                        readyFuture.complete(null);
                    }
                }
            } catch (IOException ignored) {
            }
            // The output closes when the process ends, also when stop() kills it during the start
            readyFuture.completeExceptionally(new IOException(name + " exited before it was ready"));
            executor.execute(() -> onExit(started));
        }, name + "-output");
        reader.setDaemon(true);
        reader.start();

        try {
            // A first start downloads npm/jsr modules into DENO_DIR, later starts are fast
            readyFuture.get(startTimeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            abandon(started);
            if (stops.get() != stopsAtRequest) {
                throw new CancellationException(name + " was stopped while starting");
            }
            throw new IOException(name + " failed to start", e instanceof ExecutionException ? e.getCause() : e);
        } catch (InterruptedException e) {
            abandon(started);
            throw e;
        }

        ready = started;
        startedAt = System.currentTimeMillis();
        afterStart();
        log.accept("[" + name + "] ready on " + getUrl() + " after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
    }

    private void abandon(Process started) {
        process.compareAndSet(started, null);
        destroy(started);
    }

    /** Runs on the executor. */
    private void onExit(Process exited) {
        // Exits after stop(), of a failed start or of an already replaced process are no crash
        if (!process.compareAndSet(exited, null)) {
            return;
        }

        log.accept("[" + name + "] exited unexpectedly with code " + exitCode(exited));
        if (System.currentTimeMillis() - startedAt > STABLE_UPTIME_MS) {
            failedRestarts = 0;
        }
        scheduleRestart(stops.get());
    }

    /** Runs on the executor. */
    private void scheduleRestart(int stopsAtCrash) {
        if (++failedRestarts > maxRestarts) {
            if (maxRestarts > 0) {
                log.accept("[" + name + "] giving up after " + maxRestarts + " restarts");
            }
            return;
        }

        executor.schedule(() -> {
            if (stops.get() != stopsAtCrash || isRunning()) {
                return;
            }
            log.accept("[" + name + "] restarting");
            try {
                launch(stopsAtCrash);
            } catch (CancellationException e) {
                // Stopped in the meantime
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.accept("[" + name + "] restart failed: " + e);
                scheduleRestart(stopsAtCrash);
            }
        }, failedRestarts * 2000L, TimeUnit.MILLISECONDS);
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

    /** Runs after every successful start, before it completes. */
    protected void afterStart() {
    }
}
