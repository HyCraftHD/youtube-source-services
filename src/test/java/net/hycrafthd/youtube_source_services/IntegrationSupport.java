package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared setup for the integration tests.
 */
final class IntegrationSupport {

    private static final Pattern PLAYER_ID = Pattern.compile("player\\\\?/([A-Za-z0-9_-]{6,})\\\\?/");

    private IntegrationSupport() {
    }

    /** One runtime directory for all integration tests, so Deno and the services are downloaded once per build. */
    static DenoRuntime runtime() throws IOException {
        String dir = System.getProperty("youtube_source_services.test.dir");
        Path base = dir != null ? Paths.get(dir) : Files.createTempDirectory("youtube-source-services");
        return new DenoRuntime(base);
    }

    static void log(String line) {
        System.out.println(line);
    }

    /** URL of YouTube's current web player script, as yt-cipher receives it from youtube-source. */
    static String currentPlayerUrl() throws IOException {
        String api = FileUtils.fetchString("https://www.youtube.com/iframe_api");
        Matcher matcher = PLAYER_ID.matcher(api);
        if (!matcher.find()) {
            throw new IOException("No player id found in iframe_api response");
        }
        return "https://www.youtube.com/s/player/" + matcher.group(1) + "/player_ias.vflset/en_US/base.js";
    }

    static Response get(String url, String token) throws IOException {
        return request("GET", url, token, null);
    }

    static Response post(String url, String token, String json) throws IOException {
        return request("POST", url, token, json);
    }

    private static Response request(String method, String url, String token, String json) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(60_000);
            if (token != null) {
                connection.setRequestProperty("Authorization", token);
            }
            if (json != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = "";
            if (body != null) {
                try (InputStream input = body) {
                    text = new String(FileUtils.readAll(input), StandardCharsets.UTF_8);
                }
            }
            return new Response(status, text);
        } finally {
            connection.disconnect();
        }
    }

    static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("Condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(200);
        }
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }
}
