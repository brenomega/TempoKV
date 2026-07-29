package io.tempokv.bootstrap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Performs a functional RESP PING readiness check for container orchestration. */
public final class RespHealthProbe {
    private static final byte[] PING =
            "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PONG =
            "+PONG\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int TIMEOUT_MILLIS = 2_000;

    private RespHealthProbe() {
    }

    /** Exits successfully only when the target returns the canonical RESP PONG. */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: RespHealthProbe <host> <port>");
            System.exit(2);
        }
        try {
            int port = Integer.parseInt(args[1]);
            if (!ping(args[0], port)) {
                System.exit(1);
            }
        } catch (NumberFormatException exception) {
            System.err.println("Invalid port");
            System.exit(2);
        } catch (IOException exception) {
            System.exit(1);
        }
    }

    static boolean ping(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.getOutputStream().write(PING);
            socket.getOutputStream().flush();
            return Arrays.equals(
                    PONG, socket.getInputStream().readNBytes(PONG.length));
        }
    }
}
