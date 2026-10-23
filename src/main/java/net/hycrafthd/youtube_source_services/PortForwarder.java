package net.hycrafthd.youtube_source_services;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps a fixed port on 127.0.0.1 open and forwards each connection as raw TCP to the port the service currently runs
 * on. The fixed port never changes and cannot be taken by another program in between, while the service gets a fresh
 * port on every start. Connections are closed right away while the service is not running.
 */
final class PortForwarder implements Closeable {

    private static final int CONNECT_TIMEOUT_MS = 2_000;

    private final ServerSocket server;
    private final ExecutorService executor;
    private volatile int target;

    PortForwarder(String name) throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());

        AtomicInteger threads = new AtomicInteger();
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, name + "-forward-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::accept);
    }

    int getPort() {
        return server.getLocalPort();
    }

    /** @param port where connections go from now on, or 0 to refuse them */
    void setTarget(int port) {
        target = port;
    }

    int getTarget() {
        return target;
    }

    /** Stops accepting connections and frees the port. Open connections end with the service. */
    @Override
    public void close() throws IOException {
        server.close();
        executor.shutdown();
    }

    private void accept() {
        while (!server.isClosed()) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                // Closed, or a single failed accept, for example when too many files are open
                if (!server.isClosed()) {
                    pause();
                }
                continue;
            }
            executor.execute(() -> connect(client));
        }
    }

    private void connect(Socket client) {
        int port = target;
        if (port == 0) {
            closeQuietly(client);
            return;
        }

        Socket service = new Socket();
        try {
            service.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            service.setTcpNoDelay(true);
        } catch (IOException e) {
            closeQuietly(service);
            closeQuietly(client);
            return;
        }

        // Both sockets are closed once both directions have ended
        AtomicInteger open = new AtomicInteger(2);
        executor.execute(() -> pump(client, service, open));
        pump(service, client, open);
    }

    private static void pump(Socket from, Socket to, AtomicInteger open) {
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
            // Pass the end of the request or response on, the other direction may still be busy
            to.shutdownOutput();
        } catch (IOException e) {
            // A reset on one side ends both directions
            closeQuietly(from);
            closeQuietly(to);
        }

        if (open.decrementAndGet() == 0) {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** A port that is free right now, for the service to listen on internally. */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
