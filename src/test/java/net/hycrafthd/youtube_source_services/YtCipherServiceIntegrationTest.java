package net.hycrafthd.youtube_source_services;

import net.hycrafthd.youtube_source_services.IntegrationSupport.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
        service.start();
        playerUrl = IntegrationSupport.currentPlayerUrl();
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.close();
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
    void rejectsRequestsWithoutToken() throws Exception {
        assertEquals(401, IntegrationSupport.post(service.getUrl() + "/get_sts", null, "{}").status);
    }
}
