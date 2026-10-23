package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Settings from the packaged {@code services.properties}.
 */
final class Config {

    private static final Config INSTANCE = load();

    private final Properties properties;

    Config(Properties properties) {
        this.properties = properties;
    }

    static Config get() {
        return INSTANCE;
    }

    private static Config load() {
        Properties properties = new Properties();

        try (InputStream input = Config.class.getResourceAsStream("services.properties")) {
            if (input == null) {
                throw new IllegalStateException("services.properties is missing");
            }
            properties.load(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Config(properties);
    }

    String getString(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("services.properties has no value for " + key);
        }
        return value.trim();
    }

    int getInt(String key) {
        String value = getString(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("services.properties has no number for " + key + ": " + value);
        }
    }

    /**
     * Replaces {@code {name}} placeholders in a template.
     *
     * @param values alternating placeholder names and their values
     */
    static String format(String template, String... values) {
        String result = template;
        for (int i = 0; i + 1 < values.length; i += 2) {
            result = result.replace("{" + values[i] + "}", values[i + 1]);
        }
        if (result.matches(".*\\{[a-z-]+}.*")) {
            throw new IllegalStateException("Unknown placeholder in " + template);
        }
        return result;
    }
}
