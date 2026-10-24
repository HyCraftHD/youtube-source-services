package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the process lifecycle with a minimal service, independent of the third-party services.
 */
@Tag("integration")
class DenoServiceIntegrationTest {

    private TestService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void startsOnLocalhostAndRequiresToken() throws Exception {
        service = new TestService(5);
        assertFalse(service.isRunning());

        await(service.start());

        assertTrue(service.isRunning());
        assertTrue(service.getUrl().startsWith("http://127.0.0.1:"));
        assertEquals(48, service.getToken().length());
        assertEquals(200, IntegrationSupport.get(service.getUrl(), service.getToken()).status);
        assertEquals(401, IntegrationSupport.get(service.getUrl(), null).status);
        assertEquals(401, IntegrationSupport.get(service.getUrl(), "wrong").status);
    }

    @Test
    void startIsIdempotent() throws Exception {
        service = new TestService(5);

        CompletableFuture<Void> first = service.start();
        CompletableFuture<Void> second = service.start();
        await(first);
        await(second);

        assertEquals(1, service.starts);
    }

    @Test
    void startsWhilePendingShareOneLaunch() throws Exception {
        service = new TestService(5);
        service.environment.put("START_DELAY_MS", "2000");

        List<CompletableFuture<Void>> starts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            starts.add(service.start());
        }
        for (CompletableFuture<Void> start : starts) {
            await(start);
        }

        assertEquals(1, service.launches);
        assertEquals(1, service.starts);
    }

    @Test
    void startsWhilePendingShareAFailureAndTheNextStartRetries() throws Exception {
        service = new TestService(5);
        service.environment.put("EXIT_IMMEDIATELY", "1");

        List<CompletableFuture<Void>> starts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            starts.add(service.start());
        }
        for (CompletableFuture<Void> start : starts) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> start.get(2, TimeUnit.MINUTES));
            assertInstanceOf(IOException.class, error.getCause());
        }
        assertEquals(1, service.launches);

        service.environment.remove("EXIT_IMMEDIATELY");
        await(service.start());
        assertEquals(2, service.launches);
        assertTrue(service.isRunning());
    }

    @Test
    void cancellingOneStartDoesNotCancelTheOthers() throws Exception {
        service = new TestService(5);
        service.environment.put("START_DELAY_MS", "2000");

        CompletableFuture<Void> cancelled = service.start();
        CompletableFuture<Void> other = service.start();
        cancelled.cancel(false);

        await(other);
        assertTrue(service.isRunning());
        assertEquals(1, service.launches);
    }

    @Test
    void startRightAfterStopIsNotCancelledWithThePendingStart() throws Exception {
        service = new TestService(5);
        service.environment.put("START_DELAY_MS", "30000");
        CompletableFuture<Void> first = service.start();
        IntegrationSupport.waitUntil(() -> service.launched, 30_000);

        service.stop();
        service.environment.remove("START_DELAY_MS");
        CompletableFuture<Void> second = service.start();

        assertThrows(CancellationException.class, () -> first.get(10, TimeUnit.SECONDS));
        await(second);
        assertTrue(service.isRunning());
        assertEquals(2, service.launches);
    }

    @Test
    void stopEndsTheProcess() throws Exception {
        service = new TestService(5);
        await(service.start());

        service.stop();

        assertFalse(service.isRunning());
        assertEquals(0, service.getInternalPort());
        assertRefusedQuickly();
    }

    @Test
    void startsAgainAfterStopOnTheSamePort() throws Exception {
        service = new TestService(5);
        await(service.start());
        String url = service.getUrl();
        service.stop();

        await(service.start());

        assertTrue(service.isRunning());
        assertEquals(url, service.getUrl());
        assertEquals(200, IntegrationSupport.get(url, service.getToken()).status);
        assertEquals(2, service.starts);
    }

    @Test
    void stopCancelsAStartThatIsStillRunning() throws Exception {
        service = new TestService(5);
        service.environment.put("START_DELAY_MS", "30000");
        CompletableFuture<Void> start = service.start();
        IntegrationSupport.waitUntil(() -> service.launched, 30_000);

        long before = System.currentTimeMillis();
        service.stop();
        // A future completed with a CancellationException counts as cancelled and throws it unwrapped
        assertThrows(CancellationException.class, () -> start.get(10, TimeUnit.SECONDS));
        assertTrue(start.isCancelled());
        assertTrue(System.currentTimeMillis() - before < 10_000, "stop() waited for the start");
        assertFalse(service.isRunning());

        // And it can still be started afterwards
        service.environment.remove("START_DELAY_MS");
        await(service.start());
        assertTrue(service.isRunning());
    }

    @Test
    void restartsOnTheSamePortAfterACrash() throws Exception {
        service = new TestService(5);
        await(service.start());
        String url = service.getUrl();

        IntegrationSupport.get(url + "/crash", service.getToken());
        IntegrationSupport.waitUntil(() -> service.starts == 2 && service.isRunning(), 30_000);

        assertEquals(url, service.getUrl());
        assertEquals(200, IntegrationSupport.get(url, service.getToken()).status);
    }

    @Test
    void doesNotRestartWhenDisabled() throws Exception {
        service = new TestService(0);
        await(service.start());

        IntegrationSupport.get(service.getUrl() + "/crash", service.getToken());
        IntegrationSupport.waitUntil(() -> !service.isRunning(), 10_000);
        Thread.sleep(3_000);

        assertFalse(service.isRunning());
        assertEquals(1, service.starts);
    }

    @Test
    void retriesWithAnotherInternalPortWhenItIsTaken() throws Exception {
        service = new TestService(5);
        try (ServerSocket taken = new ServerSocket(0, 1, PortForwarder.LOOPBACK)) {
            service.takenPort = taken.getLocalPort();

            await(service.start());

            assertTrue(service.isRunning());
            assertNotEquals(taken.getLocalPort(), service.getInternalPort());
            assertEquals(200, IntegrationSupport.get(service.getUrl(), service.getToken()).status);
        }
    }

    @Test
    void refusesRequestsQuicklyWhileTheServiceIsDown() throws Exception {
        service = new TestService(0);
        await(service.start());

        IntegrationSupport.get(service.getUrl() + "/crash", service.getToken());
        IntegrationSupport.waitUntil(() -> !service.isRunning(), 10_000);

        assertEquals(0, service.getInternalPort());
        assertRefusedQuickly();
    }

    @Test
    void endsWhenTheJvmIsKilled() throws Exception {
        // A separate JVM runs the service and is then killed, so its shutdown hooks cannot stop the process
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> command = Arrays.asList(java, "-cp", System.getProperty("java.class.path"), TestService.class.getName(),
            IntegrationSupport.runtime().getBaseDir().toString());
        Process jvm = new ProcessBuilder(command).redirectErrorStream(true).start();

        String url;
        String token;
        try {
            String[] ready = awaitReadyLine(jvm);
            url = "http://127.0.0.1:" + ready[1];
            token = ready[2];
            // Straight to the Deno process, past the forwarder that dies with the JVM anyway
            assertEquals(200, IntegrationSupport.get(url, token).status);
        } finally {
            jvm.destroyForcibly();
        }
        assertTrue(jvm.waitFor(30, TimeUnit.SECONDS));

        IntegrationSupport.waitUntil(() -> {
            try {
                IntegrationSupport.get(url, token);
                return false;
            } catch (IOException e) {
                return true;
            }
        }, 15_000);
    }

    @Test
    void doesNotRestartAfterStop() throws Exception {
        service = new TestService(5);
        await(service.start());

        service.stop();
        Thread.sleep(4_000);

        assertFalse(service.isRunning());
        assertEquals(1, service.starts);
    }

    @Test
    void failsWhenTheProcessExitsBeforeItIsReady() throws Exception {
        service = new TestService(5);
        service.environment.put("EXIT_IMMEDIATELY", "1");

        ExecutionException error = assertThrows(ExecutionException.class, () -> service.start().get(2, TimeUnit.MINUTES));

        assertInstanceOf(IOException.class, error.getCause());
        Throwable cause = error.getCause().getCause();
        assertTrue(cause != null && String.valueOf(cause.getMessage()).contains("exited before it was ready"), error.toString());
        assertFalse(service.isRunning());
    }

    /** The fixed port stays open, but connections are closed right away instead of running into a timeout. */
    private void assertRefusedQuickly() {
        long before = System.currentTimeMillis();
        assertThrows(IOException.class, () -> IntegrationSupport.get(service.getUrl(), service.getToken()));
        assertTrue(System.currentTimeMillis() - before < 5_000, "the request was not refused quickly");
    }

    /** Reads the child JVM's output until the READY line, failing if it ends first. Keeps draining it afterwards. */
    private static String[] awaitReadyLine(Process jvm) throws Exception {
        BufferedReader output = new BufferedReader(new InputStreamReader(jvm.getInputStream(), StandardCharsets.UTF_8));
        CompletableFuture<String[]> ready = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = output.readLine()) != null) {
                    IntegrationSupport.log("[child jvm] " + line);
                    if (line.startsWith("READY ")) {
                        ready.complete(line.split(" "));
                    }
                }
            } catch (IOException ignored) {
            }
            ready.completeExceptionally(new IOException("The child JVM ended before the service was ready"));
        });
        reader.setDaemon(true);
        reader.start();
        return ready.get(2, TimeUnit.MINUTES);
    }

    private static void await(CompletableFuture<Void> future) throws Exception {
        future.get(2, TimeUnit.MINUTES);
    }
}
