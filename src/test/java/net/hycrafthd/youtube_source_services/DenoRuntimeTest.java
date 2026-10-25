package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DenoRuntimeTest {

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    @TempDir
    Path temp;

    @Test
    void doesNotCreateTheBaseDirectory() throws IOException {
        Path base = temp.resolve("a").resolve("b");

        DenoRuntime runtime = new DenoRuntime(base);

        assertFalse(Files.exists(base));
        assertEquals(temp.toRealPath().resolve("a").resolve("b"), runtime.getBaseDir());
    }

    /** Windows keeps symbolic links in canonical paths, but they are rare there and short names are the problem. */
    @Test
    void resolvesSymbolicLinks() throws IOException {
        assumeFalse(WINDOWS, "canonical paths keep symbolic links on Windows");
        Path real = Files.createDirectories(temp.resolve("real"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), real);

        assertEquals(real.toRealPath(), new DenoRuntime(link).getBaseDir());
    }

    /** Deno checks the long name against --allow-read, so a short name such as C:\Users\EXAMPL~1 must be resolved. */
    @Test
    void resolvesWindowsShortNames() throws IOException, InterruptedException {
        assumeTrue(WINDOWS, "short names only exist on Windows");
        Path real = Files.createDirectories(temp.resolve("directory-with-a-long-name")).toRealPath();
        Path shortName = shortName(real);
        assumeFalse(shortName.equals(real), "short names are disabled on this volume");

        assertEquals(real, new DenoRuntime(shortName).getBaseDir());
        assertEquals(real.resolve("not-created"), new DenoRuntime(shortName.resolve("not-created")).getBaseDir());
    }

    /** A batch file avoids passing the quotes and percent signs of the for command through the command line. */
    private Path shortName(Path path) throws IOException, InterruptedException {
        Path script = temp.resolve("short-name.bat");
        FileUtils.writeString(script, "@for %%I in (\"" + path + "\") do @echo %%~sI\r\n");
        Process process = new ProcessBuilder("cmd", "/c", script.toString()).redirectErrorStream(true).start();
        String output = new String(FileUtils.readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return Paths.get(output);
    }
}
