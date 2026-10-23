package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downloads one pinned Deno binary into {@code baseDir}. All services share it and its module cache.
 */
final class DenoRuntime {

    private static final int ARCHIVE_ATTEMPTS = 3;

    private final Path baseDir;
    private Path executable;

    DenoRuntime(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath();
    }

    Path getBaseDir() {
        return baseDir;
    }

    /** Deno's npm/jsr/https module cache ({@code DENO_DIR}), kept inside {@code baseDir} instead of the user's home. */
    Path getDenoDir() throws IOException {
        return Files.createDirectories(baseDir.resolve("deno-dir"));
    }

    /**
     * Downloads Deno for this platform on first use and verifies it against the checksum published with the release.
     *
     * @throws UnsupportedOperationException if Deno has no build for this platform
     */
    // Both services may ask for it at the same time on their own threads
    synchronized Path getExecutable() throws IOException {
        if (executable != null) {
            return executable;
        }

        Config config = Config.get();
        String version = config.getString("deno.version");
        Path dir = baseDir.resolve("deno-" + version);
        Path file = dir.resolve(Platform.isWindows() ? "deno.exe" : "deno");

        if (!Files.isRegularFile(file) || !Files.isExecutable(file)) {
            String url = Config.format(config.getString("deno.url"), "version", version, "target", Platform.denoTarget());
            String checksumUrl = Config.format(config.getString("deno.checksum-url"), "url", url);

            Path zip = FileUtils.download(url, baseDir.resolve("deno.zip"));
            FileUtils.verifySha256(zip, FileUtils.parseSha256(FileUtils.fetchString(checksumUrl)));
            FileUtils.deleteRecursively(dir);
            FileUtils.unzip(zip, dir, 0);
            Files.delete(zip);

            if (!file.toFile().setExecutable(true)) {
                throw new IOException("Could not make " + file + " executable");
            }
        }

        return executable = file;
    }

    /**
     * Downloads a GitHub repository at a fixed commit and extracts it into {@code target}. GitHub generates archives it
     * has not cached on the fly and streams them without a length, so a broken transfer is only noticed when the zip is
     * read; that case is retried.
     */
    void downloadGitHubArchive(String repository, String commit, Path target) throws IOException {
        String url = Config.format(Config.get().getString("github.archive-url"), "repository", repository, "commit", commit);
        Path zip = baseDir.resolve(repository.replace('/', '-') + ".zip");

        for (int attempt = 1; ; attempt++) {
            try {
                FileUtils.download(url, zip);
                FileUtils.deleteRecursively(target);
                FileUtils.unzip(zip, target, 1);
                return;
            } catch (IOException e) {
                if (attempt >= ARCHIVE_ATTEMPTS) {
                    throw e;
                }
            } finally {
                Files.deleteIfExists(zip);
            }
        }
    }
}
