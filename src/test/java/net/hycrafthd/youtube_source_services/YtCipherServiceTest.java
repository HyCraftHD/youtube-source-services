package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YtCipherServiceTest {

    @TempDir
    Path temp;

    @Test
    void patchEjsRewritesBareImports() throws IOException {
        Path solver = temp.resolve("yt").resolve("solver").resolve("solvers.ts");
        Files.createDirectories(solver.getParent());
        FileUtils.writeString(solver,
            "import { parse } from \"meriyah\";\n" +
            "import { generate } from 'astring';\n" +
            "import { other } from \"./other.ts\";\n");

        YtCipherService.patchEjs(temp);

        assertEquals(
            "import { parse } from \"npm:meriyah\";\n" +
            "import { generate } from \"npm:astring\";\n" +
            "import { other } from \"./other.ts\";\n",
            FileUtils.readString(solver));
    }

    @Test
    void patchEjsIgnoresOtherFiles() throws IOException {
        Path readme = temp.resolve("README.md");
        FileUtils.writeString(readme, "import x from \"meriyah\"");

        YtCipherService.patchEjs(temp);

        assertEquals("import x from \"meriyah\"", FileUtils.readString(readme));
    }
}
