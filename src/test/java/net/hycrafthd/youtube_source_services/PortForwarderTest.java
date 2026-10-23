package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortForwarderTest {

    private PortForwarder forwarder;
    private ServerSocket backend;

    @BeforeEach
    void setUp() throws IOException {
        forwarder = new PortForwarder("test");
        // Answers each connection with "echo:" and everything it received, after the client finished sending
        backend = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            while (!backend.isClosed()) {
                try (Socket socket = backend.accept()) {
                    byte[] request = readAll(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    output.write("echo:".getBytes(StandardCharsets.UTF_8));
                    output.write(request);
                } catch (IOException ignored) {
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        forwarder.close();
        backend.close();
    }

    @Test
    void listensOnLoopbackWithItsOwnPort() {
        assertTrue(forwarder.getPort() > 0);
        assertNotEquals(backend.getLocalPort(), forwarder.getPort());
    }

    @Test
    void forwardsBothDirectionsIncludingTheEndOfTheRequest() throws IOException {
        forwarder.setTarget(backend.getLocalPort());

        assertEquals("echo:hello", exchange("hello"));
    }

    @Test
    void forwardsLargeBodies() throws IOException {
        forwarder.setTarget(backend.getLocalPort());
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            body.append(i % 10);
        }

        assertEquals("echo:" + body, exchange(body.toString()));
    }

    @Test
    void closesConnectionsWithoutTarget() throws IOException {
        forwarder.setTarget(0);

        assertEquals("", exchange("hello"));
    }

    @Test
    void closesConnectionsWhenTheTargetIsDown() throws IOException {
        forwarder.setTarget(PortForwarder.freePort());

        assertEquals("", exchange("hello"));
    }

    @Test
    void keepsItsPortWhenTheTargetChanges() throws IOException {
        int port = forwarder.getPort();
        forwarder.setTarget(0);
        forwarder.setTarget(backend.getLocalPort());

        assertEquals(port, forwarder.getPort());
        assertEquals("echo:again", exchange("again"));
    }

    /** Sends the text, ends the request and reads the response until the connection closes. */
    private String exchange(String text) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), forwarder.getPort())) {
            socket.setSoTimeout(10_000);
            try {
                socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
                socket.shutdownOutput();
            } catch (IOException e) {
                // The forwarder may have closed the connection already
            }
            try {
                return new String(readAll(socket.getInputStream()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // A reset counts as closed
                return "";
            }
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
