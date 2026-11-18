package net.hycrafthd.youtube_source_services;

import net.hycrafthd.youtube_source_services.IntegrationSupport.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Damages installations the way cleanup tools did for users: single files deleted from the temp directory, in the
 * installation as well as in Deno's module cache, while the marker of the complete installation stays.
 */
@Tag("integration")
class ServiceRepairIntegrationTest {

    @Test
    void webPoInstallsAgainAfterPackageFilesWereDeleted() throws Exception {
        DenoRuntime runtime = IntegrationSupport.runtime();
        WebPoService service = new WebPoService(runtime, IntegrationSupport::log);
        try {
            service.start().get(10, TimeUnit.MINUTES);
            service.stop();

            // Without its package.json Deno looks for jsdom/index.js, which the users' logs show as ERR_MODULE_NOT_FOUND
            List<Path> deleted = deletePackageJsons(runtime.getBaseDir(), "jsdom");
            assertFalse(deleted.isEmpty(), "jsdom was not installed");

            service.start().get(10, TimeUnit.MINUTES);
            assertTrue(service.isRunning());
            for (Path file : deleted) {
                assertTrue(Files.exists(file), "not restored: " + file);
            }
        } finally {
            service.stop();
        }
    }

    @Test
    void ytCipherInstallsAgainAfterPackageFilesWereDeleted() throws Exception {
        DenoRuntime runtime = IntegrationSupport.runtime();
        YtCipherService service = new YtCipherService(runtime, IntegrationSupport::log);
        try {
            service.start().get(10, TimeUnit.MINUTES);
            service.stop();

            // yt-cipher loads ejs's dependencies from the cache only; webpo-generator has other versions in its node_modules
            List<Path> deleted = deletePackageJsons(runtime.getDenoDir(), "meriyah");
            deleted.addAll(deletePackageJsons(runtime.getDenoDir(), "astring"));
            assertFalse(deleted.isEmpty(), "the ejs dependencies were not cached");

            service.start().get(10, TimeUnit.MINUTES);
            String n = "ZdZIqFPQK-Ty8wId";
            Response response = IntegrationSupport.post(service.getUrl() + "/decrypt_signature", service.getToken(),
                "{\"encrypted_signature\":\"\",\"n_param\":\"" + n + "\",\"player_url\":\"" + IntegrationSupport.currentPlayerUrl() + "\"}");
            assertEquals(200, response.status, response.toString());
        } finally {
            service.stop();
        }
    }

    /** The package.json of the package's root, in node_modules ({@code .../jsdom/}) and the cache ({@code .../jsdom/<version>/}). */
    private static List<Path> deletePackageJsons(Path root, String packageName) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.getFileName().toString().equals("package.json"))
                .filter(path -> isNamed(path.getParent(), packageName) || isNamed(path.getParent().getParent(), packageName))
                .collect(Collectors.toList());
        }
        for (Path file : files) {
            Files.delete(file);
        }
        return files;
    }

    private static boolean isNamed(Path path, String name) {
        return path != null && path.getFileName() != null && path.getFileName().toString().equals(name);
    }
}
