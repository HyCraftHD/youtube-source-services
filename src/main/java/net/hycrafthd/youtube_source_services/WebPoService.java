package net.hycrafthd.youtube_source_services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs <a href="https://github.com/ashton045/webpo-generator">webpo-generator</a> unmodified.
 * Use with {@code YoutubeSourceOptions#setRemotePoToken(getUrl(), getToken())}.
 */
final class WebPoService extends DenoService {

    WebPoService(DenoRuntime runtime, Consumer<String> log) throws IOException {
        super(runtime, "webpo-generator", log);
    }

    @Override
    protected Path install() throws IOException, InterruptedException {
        Config config = Config.get();
        String commit = config.getString("webpo-generator.commit");
        Path app = dir.resolve(commit.substring(0, 7));

        if (!isInstalled(app)) {
            FileUtils.deleteRecursively(app);
            runtime.downloadGitHubArchive(config.getString("webpo-generator.repository"), commit, app);
            npmInstall(app);
            markInstalled(app);
        }
        return app;
    }

    /**
     * Installs the npm dependencies from package.json/package-lock.json. Deno runs no install scripts by default, so the
     * optional native {@code canvas} package is never built; jsdom works without it.
     */
    private void npmInstall(Path app) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(runtime.getExecutable().toString(), "install")
            .directory(app.toFile())
            .redirectErrorStream(true);
        builder.environment().put("DENO_DIR", runtime.getDenoDir().toString());
        builder.environment().put("DENO_NO_UPDATE_CHECK", "1");
        builder.environment().put("NO_COLOR", "1");

        Process process = builder.start();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                log.accept("[" + getName() + " install] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("deno install failed with exit code " + exitCode);
        }
    }

    @Override
    protected List<String> arguments(Path app) throws IOException {
        return Arrays.asList(
            "--allow-net",
            "--allow-env",
            "--allow-sys",
            "--allow-read=" + app + "," + runtime.getDenoDir(),
            "server.js"
        );
    }

    @Override
    protected Map<String, String> environment(Path app) {
        return Collections.singletonMap("WORKERS", "1");
    }

    @Override
    protected String readyMarker() {
        return Config.get().getString("webpo-generator.ready-marker");
    }

    /**
     * On Deno the first generation after a start fails (botguard.js assigns globalThis.location, which is a getter in
     * Deno workers; its cleanup then deletes the property). One throwaway request gets that out of the way.
     */
    @Override
    protected void afterStart() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(getUrl() + "/generate").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Authorization", getToken());
            connection.setRequestProperty("Content-Type", "application/json");

            try (OutputStream output = connection.getOutputStream()) {
                output.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
        } catch (IOException e) {
            log.accept("[" + getName() + "] warm-up request failed: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
