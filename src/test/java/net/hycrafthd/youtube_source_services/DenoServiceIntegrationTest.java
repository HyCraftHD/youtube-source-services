package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
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

    private static void await(CompletableFuture<Void> future) throws Exception {
        future.get(2, TimeUnit.MINUTES);
    }

    /** Serves 200 with the right token, 401 without, and exits on /crash. */
    private static final class TestService extends DenoService {

        private static final String SCRIPT = String.join("\n",
            "if (Deno.env.get('EXIT_IMMEDIATELY') === '1') Deno.exit(2);",
            "const delay = Number(Deno.env.get('START_DELAY_MS') ?? '0');",
            "if (delay > 0) await new Promise((resolve) => setTimeout(resolve, delay));",
            "const token = Deno.env.get('API_TOKEN');",
            "Deno.serve({",
            "  hostname: Deno.env.get('HOST'),",
            "  port: Number(Deno.env.get('PORT')),",
            "  onListen: () => console.log('test service listening'),",
            "}, (request) => {",
            "  if (request.headers.get('Authorization') !== token) return new Response('unauthorized', { status: 401 });",
            "  if (new URL(request.url).pathname === '/crash') setTimeout(() => Deno.exit(3), 50);",
            "  return new Response('ok');",
            "});",
            "");

        final Map<String, String> environment = new HashMap<>();
        volatile int starts;
        volatile boolean launched;
        /** Handed out as the first internal port, to simulate another program taking it. */
        volatile int takenPort;

        TestService(int maxRestarts) throws IOException {
            super(IntegrationSupport.runtime(), "test-service", IntegrationSupport::log, 120, maxRestarts);
        }

        @Override
        protected Path install() throws IOException {
            Path app = Files.createDirectories(dir.resolve("app"));
            FileUtils.writeString(app.resolve("server.js"), SCRIPT);
            return app;
        }

        @Override
        protected List<String> arguments(Path app) {
            launched = true;
            return Arrays.asList("--allow-net=127.0.0.1", "--allow-env", "server.js");
        }

        @Override
        protected Map<String, String> environment(Path app) {
            return new HashMap<>(environment);
        }

        @Override
        int internalPort() throws IOException {
            int port = takenPort;
            if (port != 0) {
                takenPort = 0;
                return port;
            }
            return super.internalPort();
        }

        @Override
        protected String readyMarker() {
            return "test service listening";
        }

        @Override
        protected void afterStart() {
            starts++;
        }
    }
}
