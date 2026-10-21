package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @Test
    void denoVersionIsATag() {
        assertTrue(Versions.DENO.matches("v\\d+\\.\\d+\\.\\d+"), Versions.DENO);
    }

    @Test
    void commitsAreFullHashes() {
        // Short hashes or branch names would make the downloads change without notice
        for (String commit : new String[] {Versions.YT_CIPHER_COMMIT, Versions.EJS_COMMIT, Versions.WEBPO_GENERATOR_COMMIT}) {
            assertTrue(commit.matches("[0-9a-f]{40}"), commit);
        }
    }
}
