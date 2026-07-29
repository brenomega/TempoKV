package io.tempokv.integration;

import io.tempokv.bootstrap.TempoKvServer;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Drives the public SQL socket and reads its blank-line-delimited tabular responses.
 */
final class SqlTestClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedInputStream input;

    private SqlTestClient(Socket socket) throws IOException {
        this.socket = socket;
        this.input = new BufferedInputStream(socket.getInputStream());
    }

    /** Connects to the SQL endpoint exposed by a ready server. */
    static SqlTestClient connect(TempoKvServer server) throws IOException {
        Socket socket = new Socket("127.0.0.1", server.sqlPort());
        socket.setSoTimeout(5_000);
        return new SqlTestClient(socket);
    }

    /** Sends one or more complete SQL statements without waiting between them. */
    void send(String sql) throws IOException {
        socket.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    /** Sends two fragments to exercise statement reconstruction across network reads. */
    void sendFragments(String first, String second) throws IOException {
        socket.getOutputStream().write(first.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        socket.getOutputStream().write(second.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    /** Reads exactly one table or error response through its terminating blank line. */
    String readResponse() throws IOException {
        StringBuilder response = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = input.read()) >= 0) {
            response.append((char) current);
            if (previous == '\n' && current == '\n') {
                return response.toString();
            }
            previous = current;
        }
        throw new IOException("SQL server closed before completing its response");
    }

    /** Closes the underlying client socket. */
    @Override
    public void close() throws IOException {
        socket.close();
    }
}
