package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilsTest {

    @TempDir
    Path temp;

    @Test
    void toHex() {
        assertEquals("", FileUtils.toHex(new byte[0]));
        assertEquals("00017f80ff", FileUtils.toHex(new byte[] {0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff}));
    }

    @Test
    void sha256() throws IOException {
        Path file = temp.resolve("abc.txt");
        FileUtils.writeString(file, "abc");

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileUtils.sha256(file));
    }

    @Test
    void verifySha256AcceptsMatchIgnoringCase() throws IOException {
        Path file = temp.resolve("abc.txt");
        FileUtils.writeString(file, "abc");

        FileUtils.verifySha256(file, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD");
        assertTrue(Files.exists(file));
    }

    @Test
    void verifySha256DeletesFileOnMismatch() throws IOException {
        Path file = temp.resolve("abc.txt");
        FileUtils.writeString(file, "abc");

        assertThrows(IOException.class, () -> FileUtils.verifySha256(file, "0000"));
        assertFalse(Files.exists(file));
    }

    @Test
    void parseSha256FromSha256sumOutput() throws IOException {
        // Format of Deno's checksum files for Linux and macOS
        assertEquals("4b2f5edd2639b7470f034238b321d7002d8dfb4726d8558f52c17afac370eb2c",
            FileUtils.parseSha256("4b2f5edd2639b7470f034238b321d7002d8dfb4726d8558f52c17afac370eb2c  deno-x86_64-apple-darwin.zip\n"));
    }

    @Test
    void parseSha256FromGetFileHashOutput() throws IOException {
        // Format of Deno's checksum files for Windows
        assertEquals("cba84b26e0df5ae7f74fc66b86a95b866872a86292ebd84fa1e959927edd93bf",
            FileUtils.parseSha256("\r\n" +
                "Algorithm : SHA256\r\n" +
                "Hash      : CBA84B26E0DF5AE7F74FC66B86A95B866872A86292EBD84FA1E959927EDD93BF\r\n" +
                "Path      : C:\\a\\deno\\deno\\target\\release\\deno-x86_64-pc-windows-msvc.zip\r\n" +
                "\r\n"));
    }

    @Test
    void parseSha256RejectsFilesWithoutHash() {
        assertThrows(IOException.class, () -> FileUtils.parseSha256("Not Found"));
    }

    @Test
    void unzipStripsLeadingFolder() throws IOException {
        Path zip = zip("repo-abc123/", "repo-abc123/server.ts", "repo-abc123/src/", "repo-abc123/src/a.ts");
        Path target = temp.resolve("out");

        FileUtils.unzip(zip, target, 1);

        assertEquals("repo-abc123/server.ts", FileUtils.readString(target.resolve("server.ts")));
        assertEquals("repo-abc123/src/a.ts", FileUtils.readString(target.resolve("src").resolve("a.ts")));
        assertFalse(Files.exists(target.resolve("repo-abc123")));
    }

    @Test
    void unzipWithoutStripping() throws IOException {
        Path zip = zip("deno");
        Path target = temp.resolve("out");

        FileUtils.unzip(zip, target, 0);

        assertEquals("deno", FileUtils.readString(target.resolve("deno")));
    }

    @Test
    void unzipRejectsEntriesOutsideTarget() throws IOException {
        Path zip = zip("../evil.sh");
        Path target = temp.resolve("out");

        assertThrows(IOException.class, () -> FileUtils.unzip(zip, target, 0));
        assertFalse(Files.exists(temp.resolve("evil.sh")));
    }

    @Test
    void unzipRejectsTruncatedArchive() throws IOException {
        Path zip = zip("repo/", "repo/a.ts", "repo/b.ts", "repo/c.ts");
        byte[] bytes = Files.readAllBytes(zip);
        // Cut inside the entries, before the central directory, as an interrupted download would
        Files.write(zip, Arrays.copyOf(bytes, bytes.length / 2));

        assertThrows(IOException.class, () -> FileUtils.unzip(zip, temp.resolve("out"), 1));
    }

    @Test
    void deleteRecursively() throws IOException {
        Path dir = temp.resolve("a");
        Files.createDirectories(dir.resolve("b").resolve("c"));
        FileUtils.writeString(dir.resolve("b").resolve("file.txt"), "x");

        FileUtils.deleteRecursively(dir);
        FileUtils.deleteRecursively(dir);

        assertFalse(Files.exists(dir));
    }

    /** Creates a zip whose files contain their own entry name; names ending in / are directories. */
    private Path zip(String... entries) throws IOException {
        Path zip = temp.resolve("test.zip");
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream output = new ZipOutputStream(file)) {
            for (String entry : entries) {
                output.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) {
                    output.write(entry.getBytes(StandardCharsets.UTF_8));
                }
                output.closeEntry();
            }
        }
        return zip;
    }
}
