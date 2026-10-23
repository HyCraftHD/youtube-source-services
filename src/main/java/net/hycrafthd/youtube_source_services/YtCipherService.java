package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs <a href="https://github.com/kikkia/yt-cipher">yt-cipher</a> with the
 * <a href="https://github.com/yt-dlp/ejs">yt-dlp/ejs</a> solver.
 * Use with {@code YoutubeSourceOptions#setRemoteCipher(getUrl(), getToken(), userAgent)}.
 */
final class YtCipherService extends DenoService {

    private static final Pattern DEPENDENCIES = Pattern.compile("\"dependencies\"\\s*:\\s*\\{([^}]*)}");
    private static final Pattern ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");

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

            Map<String, String> imports = ejsImports(FileUtils.readString(app.resolve("ejs").resolve("package.json")));
            imports.putAll(pinnedImports(config.getString("yt-cipher.pinned-imports")));
            FileUtils.writeString(app.resolve("deno.json"), denoJson(imports));
            markInstalled(app);
        }
        return app;
    }

    /**
     * ejs imports its npm dependencies by bare name, which Deno cannot resolve on its own. yt-cipher patches them to
     * unversioned {@code npm:} specifiers, which makes Deno pick the latest release instead of the version ejs is built
     * for. Mapping the bare names in an import map keeps the sources unchanged and uses the versions from ejs's
     * package.json.
     */
    static Map<String, String> ejsImports(String packageJson) throws IOException {
        Matcher dependencies = DEPENDENCIES.matcher(packageJson);
        if (!dependencies.find()) {
            throw new IOException("ejs package.json has no dependencies");
        }

        Map<String, String> imports = new LinkedHashMap<>();
        Matcher entry = ENTRY.matcher(dependencies.group(1));
        while (entry.find()) {
            imports.put(entry.group(1), "npm:" + entry.group(1) + "@" + entry.group(2));
        }
        return imports;
    }

    /** Maps unversioned specifiers in yt-cipher to the versions in {@code yt-cipher.pinned-imports}. */
    static Map<String, String> pinnedImports(String pinned) {
        Map<String, String> imports = new LinkedHashMap<>();
        for (String specifier : pinned.split(",")) {
            specifier = specifier.trim();
            int version = specifier.lastIndexOf('@');
            // The @ of a scope like jsr:@std/cache is not a version separator
            if (version <= specifier.indexOf(':') + 1) {
                throw new IllegalStateException("No version in pinned import " + specifier);
            }
            imports.put(specifier.substring(0, version), specifier);
        }
        return imports;
    }

    static String denoJson(Map<String, String> imports) {
        StringBuilder json = new StringBuilder("{\n  \"imports\": {");
        String separator = "\n";
        for (Map.Entry<String, String> entry : imports.entrySet()) {
            json.append(separator).append("    ").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue()));
            separator = ",\n";
        }
        return json.append("\n  }\n}\n").toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    protected List<String> arguments(Path app) throws IOException {
        Path cache = cacheDir();
        return Arrays.asList(
            "--config=" + app.resolve("deno.json"),
            "--allow-net",
            "--allow-env",
            "--allow-read=" + app + "," + cache,
            "--allow-write=" + cache
        );
    }

    @Override
    protected String mainModule() {
        return "server.ts";
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
