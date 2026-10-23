package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs <a href="https://github.com/kikkia/yt-cipher">yt-cipher</a> with the
 * <a href="https://github.com/yt-dlp/ejs">yt-dlp/ejs</a> solver.
 * Use with {@code YoutubeSourceOptions#setRemoteCipher(getUrl(), getToken(), userAgent)}.
 */
final class YtCipherService extends DenoService {

    YtCipherService(DenoRuntime runtime, Consumer<String> log) throws IOException {
        super(runtime, "yt-cipher", log);
    }

    @Override
    protected Path install() throws IOException {
        Config config = Config.get();
        String commit = config.getString("yt-cipher.commit");
        Path app = dir.resolve(commit.substring(0, 7));

        if (!isInstalled(app)) {
            FileUtils.deleteRecursively(app);
            runtime.downloadGitHubArchive(config.getString("yt-cipher.repository"), commit, app);
            runtime.downloadGitHubArchive(config.getString("ejs.repository"), config.getString("ejs.commit"), app.resolve("ejs"));
            patchEjs(app.resolve("ejs").resolve("src"));
            markInstalled(app);
        }
        return app;
    }

    /** Same as yt-cipher's scripts/patch-ejs.ts: point bare imports at npm: specifiers so Deno can resolve them. */
    static void patchEjs(Path ejsSrc) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(ejsSrc)) {
            files = walk.filter(path -> path.toString().endsWith(".ts")).collect(Collectors.toList());
        }

        for (Path file : files) {
            String content = FileUtils.readString(file);
            String patched = content
                .replaceAll("from [\"']meriyah[\"']", "from \"npm:meriyah\"")
                .replaceAll("from [\"']astring[\"']", "from \"npm:astring\"");
            if (!patched.equals(content)) {
                FileUtils.writeString(file, patched);
            }
        }
    }

    @Override
    protected List<String> arguments(Path app) throws IOException {
        Path cache = cacheDir();
        return Arrays.asList(
            "--allow-net",
            "--allow-env",
            "--allow-read=" + app + "," + cache,
            "--allow-write=" + cache,
            "server.ts"
        );
    }

    @Override
    protected Map<String, String> environment(Path app) throws IOException {
        Map<String, String> environment = new HashMap<>();
        environment.put("MAX_THREADS", "1");
        // The player script cache goes to $XDG_CACHE_HOME/yt-cipher, which is checked before HOME/LOCALAPPDATA
        environment.put("XDG_CACHE_HOME", cacheDir().toString());
        return environment;
    }

    private Path cacheDir() throws IOException {
        return Files.createDirectories(dir.resolve("cache"));
    }

    @Override
    protected String readyMarker() {
        return Config.get().getString("yt-cipher.ready-marker");
    }
}
