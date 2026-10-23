package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    private final Config config = Config.get();

    @Test
    void denoVersionIsATag() {
        String version = config.getString("deno.version");
        assertTrue(version.matches("v\\d+\\.\\d+\\.\\d+"), version);
    }

    @Test
    void commitsAreFullHashes() {
        // Short hashes or branch names would make the downloads change without notice
        for (String key : new String[] {"yt-cipher.commit", "ejs.commit", "webpo-generator.commit"}) {
            String commit = config.getString(key);
            assertTrue(commit.matches("[0-9a-f]{40}"), key + "=" + commit);
        }
    }

    @Test
    void allKeysAreSet() {
        for (String key : new String[] {"deno.url", "deno.checksum-url", "github.archive-url", "yt-cipher.repository",
            "yt-cipher.ready-marker", "ejs.repository", "webpo-generator.repository", "webpo-generator.ready-marker"}) {
            config.getString(key);
        }
        assertTrue(config.getInt("start-timeout-seconds") > 0);
        assertTrue(config.getInt("restart.max-attempts") >= 0);
    }

    @Test
    void denoUrls() {
        String url = Config.format(config.getString("deno.url"), "version", "v2.9.8", "target", "x86_64-pc-windows-msvc");
        assertEquals("https://github.com/denoland/deno/releases/download/v2.9.8/deno-x86_64-pc-windows-msvc.zip", url);
        assertEquals(url + ".sha256sum", Config.format(config.getString("deno.checksum-url"), "url", url));
    }

    @Test
    void githubArchiveUrl() {
        assertEquals("https://codeload.github.com/kikkia/yt-cipher/zip/abc",
            Config.format(config.getString("github.archive-url"), "repository", "kikkia/yt-cipher", "commit", "abc"));
    }

    @Test
    void formatRejectsUnfilledPlaceholders() {
        assertThrows(IllegalStateException.class, () -> Config.format("https://example.com/{version}/{target}", "version", "v1"));
    }

    @Test
    void missingAndBlankValuesAreRejected() {
        Properties properties = new Properties();
        properties.setProperty("blank", " ");
        properties.setProperty("text", "abc");
        Config custom = new Config(properties);

        assertThrows(IllegalStateException.class, () -> custom.getString("missing"));
        assertThrows(IllegalStateException.class, () -> custom.getString("blank"));
        assertThrows(IllegalStateException.class, () -> custom.getInt("text"));
    }
}
