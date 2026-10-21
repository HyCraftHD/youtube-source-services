package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Pinned versions from {@code versions.properties}.
 */
final class Versions {

    static final String DENO;
    static final String YT_CIPHER_COMMIT;
    static final String EJS_COMMIT;
    static final String WEBPO_GENERATOR_COMMIT;

    static {
        Properties properties = new Properties();

        try (InputStream input = Versions.class.getResourceAsStream("versions.properties")) {
            if (input == null) {
                throw new IllegalStateException("versions.properties is missing");
            }
            properties.load(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        DENO = require(properties, "deno.version");
        YT_CIPHER_COMMIT = require(properties, "yt-cipher.commit");
        EJS_COMMIT = require(properties, "ejs.commit");
        WEBPO_GENERATOR_COMMIT = require(properties, "webpo-generator.commit");
    }

    private Versions() {
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("versions.properties has no value for " + key);
        }
        return value.trim();
    }
}
