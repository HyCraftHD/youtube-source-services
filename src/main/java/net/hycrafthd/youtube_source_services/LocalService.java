package net.hycrafthd.youtube_source_services;

/**
 * One of the services, as seen by youtube-source.
 */
public interface LocalService {

    /** Base URL on 127.0.0.1. Fixed per instance, so it can be passed to youtube-source before the service runs. */
    String getUrl();

    /** Value for the {@code Authorization} header, passed to youtube-source as the service password. Fixed per instance. */
    String getToken();

    /** Whether the process has started and accepts requests. */
    boolean isRunning();
}
