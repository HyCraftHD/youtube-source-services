package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            service.close();
        }
    }

    @Test
    void startsOnLocalhostAndRequiresToken() throws Exception {
        service = new TestService(IntegrationSupport.runtime(), IntegrationSupport::log);
        assertFalse(service.isRunning());

        service.startAsync().get(2, TimeUnit.MINUTES);

        assertTrue(service.isRunning());
        assertTrue(service.getUrl().startsWith("http://127.0.0.1:"));
        assertEquals(48, service.getToken().length());
        assertEquals(200, IntegrationSupport.get(service.getUrl(), service.getToken()).status);
        assertEquals(401, IntegrationSupport.get(service.getUrl(), null).status);
        assertEquals(401, IntegrationSupport.get(service.getUrl(), "wrong").status);
    }

    @Test
    void startIsIdempotent() throws Exception {
        service = new TestService(IntegrationSupport.runtime(), IntegrationSupport::log);

        service.start();
        service.start();

        assertEquals(1, service.starts);
    }

    @Test
    void stopEndsTheProcess() throws Exception {
        service = new TestService(IntegrationSupport.runtime(), IntegrationSupport::log);
        service.start();

        service.stop();

        assertFalse(service.isRunning());
        assertThrows(ConnectException.class, () -> IntegrationSupport.get(service.getUrl(), service.getToken()));
    }

    @Test
    void restartsOnTheSamePortAfterACrash() throws Exception {
        service = new TestService(IntegrationSupport.runtime(), IntegrationSupport::log);
        service.start();
        String url = service.getUrl();

        IntegrationSupport.get(url + "/crash", service.getToken());
        IntegrationSupport.waitUntil(() -> service.starts == 2 && service.isRunning(), 30_000);

        assertEquals(url, service.getUrl());
        assertEquals(200, IntegrationSupport.get(url, service.getToken()).status);
    }

    @Test
    void doesNotRestartWhenDisabled() throws Exception {
        service = new TestService(IntegrationSupport.runtime(), IntegrationSupport::log);
        service.setRestartOnCrash(false);
        service.start();

        IntegrationSupport.get(service.getUrl() + "/crash", service.getToken());
        IntegrationSupport.waitUntil(() -> !service.isRunning(), 10_000);
        Thread.sleep(3_000);

        assertFalse(service.isRunning());
        assertEquals(1, service.starts);
    }

    @Test
    void failsWhenTheProcessExitsBeforeItIsReady() throws Exception {
        service = new TestService(IntegrationSupport.runtime(), IntegrationSupport::log);
        service.exitImmediately = true;

        IOException error = assertThrows(IOException.class, service::start);

        assertTrue(error.getCause() != null && String.valueOf(error.getCause().getMessage()).contains("exited before it was ready"), error.toString());
        assertFalse(service.isRunning());
    }

    /** Serves 200 with the right token, 401 without, and exits on /crash. */
    private static final class TestService extends DenoService {

        private static final String SCRIPT = String.join("\n",
            "if (Deno.env.get('EXIT_IMMEDIATELY') === '1') Deno.exit(2);",
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

        volatile int starts;
        boolean exitImmediately;

        TestService(DenoRuntime runtime, Consumer<String> log) throws IOException {
            super(runtime, "test-service", log);
        }

        @Override
        protected Path install() throws IOException {
            Path app = Files.createDirectories(dir.resolve("app"));
            FileUtils.writeString(app.resolve("server.js"), SCRIPT);
            return app;
        }

        @Override
        protected List<String> arguments(Path app) {
            return Arrays.asList("--allow-net=127.0.0.1", "--allow-env", "server.js");
        }

        @Override
        protected Map<String, String> environment(Path app) {
            return Collections.singletonMap("EXIT_IMMEDIATELY", exitImmediately ? "1" : "0");
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
