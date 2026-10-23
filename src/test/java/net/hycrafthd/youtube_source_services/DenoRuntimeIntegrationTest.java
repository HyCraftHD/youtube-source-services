package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class DenoRuntimeIntegrationTest {

    @Test
    void downloadsAndRunsPinnedDeno() throws IOException, InterruptedException {
        DenoRuntime runtime = IntegrationSupport.runtime();

        Path deno = runtime.getExecutable();
        assertTrue(Files.isExecutable(deno), deno.toString());
        assertSame(deno, runtime.getExecutable(), "the executable is resolved only once");

        Process process = new ProcessBuilder(deno.toString(), "--version").redirectErrorStream(true).start();
        String output = new String(FileUtils.readAll(process.getInputStream()), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.startsWith("deno " + Config.get().getString("deno.version").substring(1) + " "), output);
    }

    @Test
    void reusesAnExistingDownload() throws IOException {
        Path first = IntegrationSupport.runtime().getExecutable();
        long modified = Files.getLastModifiedTime(first).toMillis();

        Path second = IntegrationSupport.runtime().getExecutable();

        assertEquals(first, second);
        assertEquals(modified, Files.getLastModifiedTime(second).toMillis());
    }
}
