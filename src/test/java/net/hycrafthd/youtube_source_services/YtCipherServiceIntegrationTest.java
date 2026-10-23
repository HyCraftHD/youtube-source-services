package net.hycrafthd.youtube_source_services;

import net.hycrafthd.youtube_source_services.IntegrationSupport.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls the same endpoints youtube-source's RemoteCipherManager uses.
 */
@Tag("integration")
class YtCipherServiceIntegrationTest {

    private static YtCipherService service;
    private static String playerUrl;

    @BeforeAll
    static void start() throws Exception {
        service = new YtCipherService(IntegrationSupport.runtime(), IntegrationSupport::log);
        service.start().get(10, TimeUnit.MINUTES);
        playerUrl = IntegrationSupport.currentPlayerUrl();
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void getSts() throws Exception {
        Response response = IntegrationSupport.post(service.getUrl() + "/get_sts", service.getToken(),
            "{\"player_url\":\"" + playerUrl + "\"}");

        assertEquals(200, response.status, response.toString());
        assertTrue(response.body.matches(".*\"sts\":\"?\\d+.*"), response.toString());
    }

    @Test
    void decryptsNParameter() throws Exception {
        String n = "ZdZIqFPQK-Ty8wId";
        Response response = IntegrationSupport.post(service.getUrl() + "/decrypt_signature", service.getToken(),
            "{\"encrypted_signature\":\"\",\"n_param\":\"" + n + "\",\"player_url\":\"" + playerUrl + "\"}");

        assertEquals(200, response.status, response.toString());
        assertTrue(response.body.contains("\"decrypted_n_sig\":\""), response.toString());
        assertFalse(response.body.contains("\"decrypted_n_sig\":\"" + n + "\""), "n parameter was not transformed: " + response);
    }

    @Test
    void usesTheEjsDependencyVersions() throws Exception {
        Path app = service.dir.resolve(Config.get().getString("yt-cipher.commit").substring(0, 7));
        Map<String, String> expected = YtCipherService.ejsImports(FileUtils.readString(app.resolve("ejs").resolve("package.json")));
        String lock = FileUtils.readString(app.resolve("deno.lock"));

        for (String specifier : expected.values()) {
            String versioned = specifier.substring("npm:".length());
            assertTrue(lock.contains("\"" + versioned + "\""), "deno.lock has no " + versioned + ":\n" + lock);
        }
        // ejs's sources are not patched
        assertFalse(FileUtils.readString(app.resolve("ejs").resolve("src").resolve("utils.ts")).contains("npm:"));
    }

    @Test
    void rejectsRequestsWithoutToken() throws Exception {
        assertEquals(401, IntegrationSupport.post(service.getUrl() + "/get_sts", null, "{}").status);
    }
}
