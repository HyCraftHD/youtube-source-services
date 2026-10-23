package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal service for the lifecycle tests: serves 200 with the right token, 401 without, and exits on /crash.
 */
class TestService extends DenoService {

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

    TestService(DenoRuntime runtime, int maxRestarts) throws IOException {
        super(runtime, "test-service", IntegrationSupport::log, 120, maxRestarts);
    }

    TestService(int maxRestarts) throws IOException {
        this(IntegrationSupport.runtime(), maxRestarts);
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
        return Arrays.asList("--allow-net=127.0.0.1", "--allow-env");
    }

    @Override
    protected String mainModule() {
        return "server.js";
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

    /**
     * Started in a separate JVM by the tests: runs the service in the given directory and prints its internal port and
     * token once it accepts requests.
     */
    public static void main(String[] args) throws Exception {
        TestService service = new TestService(new DenoRuntime(Paths.get(args[0])), 0);
        service.start().get();
        System.out.println("READY " + service.getInternalPort() + " " + service.getToken());
        Thread.currentThread().join();
    }
}
