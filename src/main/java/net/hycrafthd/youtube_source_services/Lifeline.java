package net.hycrafthd.youtube_source_services;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback port each Deno process connects to on startup and keeps open. The operating system closes these
 * connections when the JVM exits in any way, even when it is killed, and the processes exit once theirs closes.
 * <p>
 * stdin would be the obvious channel, but on Windows a pending read on the stdin pipe blocks every other use of that
 * handle, which hangs Deno's Node compatibility layer during startup.
 */
final class Lifeline {

    private static Lifeline instance;

    private final ServerSocket server;
    private final ExecutorService executor;

    private Lifeline() throws IOException {
        server = new ServerSocket(0, 50, PortForwarder.LOOPBACK);

        AtomicInteger threads = new AtomicInteger();
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "youtube-source-services-lifeline-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::accept);
    }

    /** The port of the one lifeline shared by all services of this JVM. */
    static synchronized int port() throws IOException {
        if (instance == null) {
            instance = new Lifeline();
        }
        return instance.server.getLocalPort();
    }

    private void accept() {
        while (true) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                pause();
                continue;
            }
            // Held open until the process closes its side, then released
            executor.execute(() -> drain(socket));
        }
    }

    private static void drain(Socket socket) {
        try (Socket ignored = socket; InputStream input = socket.getInputStream()) {
            byte[] buffer = new byte[64];
            while (input.read(buffer) != -1) {
                // Nothing is sent, this only waits for the end
            }
        } catch (IOException ignored) {
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
