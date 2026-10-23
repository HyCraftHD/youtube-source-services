package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YoutubeSourceServicesTest {

    @TempDir
    Path temp;

    @Test
    void buildingDownloadsAndStartsNothing() throws IOException {
        YoutubeSourceServices services = YoutubeSourceServices.builder(temp).build();

        assertFalse(services.isRunning());
        assertFalse(services.cipher().isRunning());
        assertFalse(services.poToken().isRunning());
        try (Stream<Path> files = Files.list(temp)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void servicesHaveDifferentPortsAndTokens() throws IOException {
        YoutubeSourceServices services = YoutubeSourceServices.builder(temp).build();

        assertNotEquals(services.cipher().getUrl(), services.poToken().getUrl());
        assertNotEquals(services.cipher().getToken(), services.poToken().getToken());
    }

    @Test
    void disabledServicesCannotBeAccessed() throws IOException {
        YoutubeSourceServices services = YoutubeSourceServices.builder(temp).poToken(false).build();

        services.cipher();
        assertThrows(IllegalStateException.class, services::poToken);
    }

    @Test
    void atLeastOneServiceMustBeEnabled() {
        assertThrows(IllegalStateException.class, () -> YoutubeSourceServices.builder(temp).cipher(false).poToken(false).build());
    }

    @Test
    void stopBeforeStartIsHarmless() throws IOException {
        YoutubeSourceServices services = YoutubeSourceServices.builder(temp).build();

        services.stop();

        assertFalse(services.isRunning());
    }
}
