package net.hycrafthd.youtube_source_services;

import net.hycrafthd.youtube_source_services.IntegrationSupport.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls the same endpoint youtube-source's RemotePoToken uses.
 */
@Tag("integration")
class WebPoServiceIntegrationTest {

    private static WebPoService service;

    @BeforeAll
    static void start() throws Exception {
        service = new WebPoService(IntegrationSupport.runtime(), IntegrationSupport::log);
        service.start().get(10, TimeUnit.MINUTES);
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void generatesContentBoundToken() throws Exception {
        Response response = IntegrationSupport.post(service.getUrl() + "/generate", service.getToken(),
            "{\"content_binding\":\"dQw4w9WgXcQ\"}");

        assertEquals(200, response.status, response.toString());
        assertTrue(response.body.contains("\"poToken\":\""), response.toString());
        assertTrue(response.body.contains("\"contentBinding\":\"dQw4w9WgXcQ\""), response.toString());
    }

    @Test
    void generatesVisitorBoundToken() throws Exception {
        // Without the warm-up in afterStart(), the first of these fails on Deno
        Response response = IntegrationSupport.post(service.getUrl() + "/generate", service.getToken(), "{}");

        assertEquals(200, response.status, response.toString());
        assertTrue(response.body.contains("\"poToken\":\""), response.toString());
    }

    @Test
    void rejectsRequestsWithoutToken() throws Exception {
        assertEquals(401, IntegrationSupport.post(service.getUrl() + "/generate", null, "{}").status);
    }
}
