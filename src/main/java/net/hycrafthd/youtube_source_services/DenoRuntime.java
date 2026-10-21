package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downloads one pinned Deno binary into {@code baseDir}. All services share it and its module cache.
 */
public final class DenoRuntime {

    private static final int ARCHIVE_ATTEMPTS = 3;

    private final Path baseDir;
    private Path executable;

    public DenoRuntime(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath();
    }

    public Path getBaseDir() {
        return baseDir;
    }

    /** Deno's npm/jsr/https module cache ({@code DENO_DIR}), kept inside {@code baseDir} instead of the user's home. */
    public Path getDenoDir() throws IOException {
        return Files.createDirectories(baseDir.resolve("deno-dir"));
    }

    /**
     * Downloads Deno for this platform on first use and verifies it against the checksum published with the release.
     *
     * @throws UnsupportedOperationException if Deno has no build for this platform
     */
    public synchronized Path getExecutable() throws IOException {
        if (executable != null) {
            return executable;
        }

        Path dir = baseDir.resolve("deno-" + Versions.DENO);
        Path file = dir.resolve(Platform.isWindows() ? "deno.exe" : "deno");

        if (!Files.isRegularFile(file) || !Files.isExecutable(file)) {
            String asset = "deno-" + Platform.denoTarget() + ".zip";
            String url = "https://github.com/denoland/deno/releases/download/" + Versions.DENO + "/" + asset;

            Path zip = FileUtils.download(url, baseDir.resolve(asset));
            FileUtils.verifySha256(zip, FileUtils.parseSha256(FileUtils.fetchString(url + ".sha256sum")));
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
        String url = "https://codeload.github.com/" + repository + "/zip/" + commit;
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
