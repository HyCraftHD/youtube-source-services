package net.hycrafthd.youtube_source_services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Download, checksum and archive helpers using only Java 8 APIs.
 */
final class FileUtils {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FileUtils() {
    }

    /**
     * Downloads to {@code target.part} first and renames it on success, so an interrupted download never leaves a
     * truncated file under the final name.
     */
    static Path download(String url, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        HttpURLConnection connection = open(url);

        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download of " + url + " failed with HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            long expected = connection.getContentLengthLong();
            long actual = Files.size(temp);
            if (expected >= 0 && actual != expected) {
                throw new IOException("Download of " + url + " is incomplete: " + actual + " of " + expected + " bytes");
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        } finally {
            connection.disconnect();
        }

        return Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static String fetchString(String url) throws IOException {
        HttpURLConnection connection = open(url);

        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Request to " + url + " failed with HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return new String(readAll(input), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        // GitHub release downloads redirect to a CDN; same-protocol redirects are followed automatically
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "youtube-source-services");
        // Java 8 sends "text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2" by default, which GitHub answers with 406
        connection.setRequestProperty("Accept", "*/*");
        return connection;
    }

    static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** Deletes the file and throws if its SHA-256 does not match, so the next attempt downloads it again. */
    static void verifySha256(Path file, String expected) throws IOException {
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            Files.delete(file);
            throw new IOException("Checksum mismatch for " + file.getFileName() + ": expected " + expected + ", got " + actual);
        }
    }

    static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            chars[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(chars);
    }

    /**
     * Extracts a zip, dropping the first {@code stripComponents} path segments (GitHub archives wrap everything in one
     * folder). Entries that would end up outside {@code target} are rejected.
     * <p>
     * Uses {@link ZipFile}, which reads the central directory at the end of the archive, so a truncated download fails
     * here. {@code ZipInputStream} would silently stop at the cut and leave a partial installation.
     */
    static void unzip(Path zip, Path target, int stripComponents) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();

        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                List<String> parts = Arrays.asList(entry.getName().split("/"));
                if (parts.size() <= stripComponents) {
                    continue;
                }

                Path out = root.resolve(String.join("/", parts.subList(stripComponents, parts.size()))).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Zip entry outside target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream input = file.getInputStream(entry)) {
                        Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(path)) {
            files = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path file : files) {
            Files.delete(file);
        }
    }

    static String readString(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    static void writeString(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
