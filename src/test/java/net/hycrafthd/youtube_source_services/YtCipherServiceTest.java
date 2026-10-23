package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YtCipherServiceTest {

    @Test
    void ejsImportsUseThePackageJsonVersions() throws IOException {
        String packageJson = "{\n" +
            "  \"name\": \"ejs\",\n" +
            "  \"scripts\": {\n    \"bundle\": \"rollup -c\"\n  },\n" +
            "  \"dependencies\": {\n    \"astring\": \"1.9.0\",\n    \"meriyah\": \"6.1.4\"\n  },\n" +
            "  \"devDependencies\": {\n    \"rollup\": \"4.52.5\"\n  }\n" +
            "}\n";

        Map<String, String> imports = YtCipherService.ejsImports(packageJson);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("astring", "npm:astring@1.9.0");
        expected.put("meriyah", "npm:meriyah@6.1.4");
        assertEquals(expected, imports);
    }

    @Test
    void ejsImportsRequireDependencies() {
        assertThrows(IOException.class, () -> YtCipherService.ejsImports("{\"name\": \"ejs\"}"));
    }

    @Test
    void pinnedImportsMapTheUnversionedSpecifier() {
        Map<String, String> imports = YtCipherService.pinnedImports("jsr:@std/cache@0.2.4, npm:foo@1.0.0");

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("jsr:@std/cache", "jsr:@std/cache@0.2.4");
        expected.put("npm:foo", "npm:foo@1.0.0");
        assertEquals(expected, imports);
    }

    @Test
    void pinnedImportsRequireAVersion() {
        // The @ of the scope is no version
        assertThrows(IllegalStateException.class, () -> YtCipherService.pinnedImports("jsr:@std/cache"));
        assertThrows(IllegalStateException.class, () -> YtCipherService.pinnedImports("npm:foo"));
    }

    @Test
    void pinnedImportsFromTheConfigAreValid() {
        YtCipherService.pinnedImports(Config.get().getString("yt-cipher.pinned-imports"));
    }

    @Test
    void denoJsonContainsAnImportMap() {
        Map<String, String> imports = new LinkedHashMap<>();
        imports.put("meriyah", "npm:meriyah@6.1.4");
        imports.put("jsr:@std/cache", "jsr:@std/cache@0.2.4");

        assertEquals("{\n" +
            "  \"imports\": {\n" +
            "    \"meriyah\": \"npm:meriyah@6.1.4\",\n" +
            "    \"jsr:@std/cache\": \"jsr:@std/cache@0.2.4\"\n" +
            "  }\n" +
            "}\n", YtCipherService.denoJson(imports));
    }
}
