package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Runs the services youtube-source can use as local processes, sharing one Deno runtime in {@code baseDir}:
 * <ul>
 *     <li>{@link #cipher()}: yt-cipher, for {@code YoutubeSourceOptions#setRemoteCipher(url, token, userAgent)}</li>
 *     <li>{@link #poToken()}: webpo-generator, for {@code YoutubeSourceOptions#setRemotePoToken(url, token)}</li>
 * </ul>
 * URL and token of each service are fixed per instance, so they can be passed to youtube-source before the services
 * run. The services can be stopped and started again as often as needed, are restarted automatically after a crash
 * and are stopped when the JVM exits.
 */
public final class YoutubeSourceServices {

    private final YtCipherService cipher;
    private final WebPoService poToken;
    private final List<DenoService> services = new ArrayList<>();

    private YoutubeSourceServices(Builder builder) throws IOException {
        DenoRuntime runtime = new DenoRuntime(builder.baseDir);
        cipher = builder.cipher ? new YtCipherService(runtime, builder.log) : null;
        poToken = builder.poToken ? new WebPoService(runtime, builder.log) : null;

        if (cipher != null) {
            services.add(cipher);
        }
        if (poToken != null) {
            services.add(poToken);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "youtube-source-services-shutdown"));
    }

    /**
     * @param baseDir where Deno, the services and their caches are stored (about 200 MB), for example a folder in the
     *                game directory
     */
    public static Builder builder(Path baseDir) {
        return new Builder(baseDir);
    }

    /**
     * Starts all enabled services in parallel in the background. The first start downloads Deno and the services, so
     * never wait for the result on the Minecraft render thread.
     * <p>
     * Can be called as often as needed, for example before every load: completes right away while the services run,
     * shares a start that is still pending, and only starts them again once they are down, so a failed start can be
     * retried. Nothing is downloaded or launched before the first call.
     *
     * @return completes once all services accept requests, or exceptionally if one fails to start or is stopped first
     */
    public CompletableFuture<Void> start() {
        return CompletableFuture.allOf(services.stream().map(DenoService::start).toArray(CompletableFuture[]::new));
    }

    /** Stops all services, including ones that are still starting. They can be started again afterwards. */
    public void stop() {
        services.forEach(DenoService::stop);
    }

    /** Whether all enabled services accept requests. */
    public boolean isRunning() {
        return services.stream().allMatch(DenoService::isRunning);
    }

    /** @throws IllegalStateException if the service was disabled in the builder */
    public LocalService cipher() {
        if (cipher == null) {
            throw new IllegalStateException("The cipher service is disabled");
        }
        return cipher;
    }

    /** @throws IllegalStateException if the service was disabled in the builder */
    public LocalService poToken() {
        if (poToken == null) {
            throw new IllegalStateException("The poToken service is disabled");
        }
        return poToken;
    }

    public static final class Builder {

        private final Path baseDir;
        private Consumer<String> log = line -> {
        };
        private boolean cipher = true;
        private boolean poToken = true;

        private Builder(Path baseDir) {
            this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        }

        /** Receives the output of downloads and services, one line at a time. Discarded by default. */
        public Builder log(Consumer<String> log) {
            this.log = Objects.requireNonNull(log, "log");
            return this;
        }

        /** Whether to run yt-cipher. Enabled by default. */
        public Builder cipher(boolean enabled) {
            this.cipher = enabled;
            return this;
        }

        /** Whether to run webpo-generator, for example disabled when an own poToken server is used. Enabled by default. */
        public Builder poToken(boolean enabled) {
            this.poToken = enabled;
            return this;
        }

        /** Chooses the ports and tokens. Nothing is downloaded or started yet. */
        public YoutubeSourceServices build() throws IOException {
            if (!cipher && !poToken) {
                throw new IllegalStateException("At least one service must be enabled");
            }
            return new YoutubeSourceServices(this);
        }
    }
}
