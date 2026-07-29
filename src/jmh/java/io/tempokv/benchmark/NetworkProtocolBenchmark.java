package io.tempokv.benchmark;

import io.tempokv.application.Command;
import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandHandler;
import io.tempokv.application.CommandResult;
import io.tempokv.application.CommandValidator;
import io.tempokv.application.KeyValueCommand;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.RespServer;
import io.tempokv.server.Session;
import io.tempokv.server.SqlServer;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class NetworkProtocolBenchmark {
    @State(Scope.Benchmark)
    public static class EndpointState {
        @Param({"1", "16", "128"})
        public int pipelineSize;

        private RespServer resp;
        private SqlServer sql;
        private byte[] pipeline;

        @Setup
        public void setup() throws IOException {
            MetricsRegistry metrics = new MetricsRegistry();
            CommandHandler<Command> handler = new CommandHandler<>() {
                @Override
                public Class<Command> commandType() {
                    return Command.class;
                }

                @Override
                public CommandResult handle(
                        Command command, Session session) {
                    if (command instanceof KeyValueCommand keyValue
                            && keyValue.kind()
                                    == KeyValueCommand.Kind.GET
                            || command.name().equals("GETAT")) {
                        return new CommandResult.BulkString(
                                "benchmark-value".getBytes(
                                        StandardCharsets.UTF_8));
                    }
                    return CommandResult.simpleString(
                            command.name().equals("PING") ? "PONG" : "OK");
                }
            };
            CommandDispatcher dispatcher = new CommandDispatcher(
                    new CommandValidator(), List.of(handler));
            resp = new RespServer(0, metrics, dispatcher);
            sql = new SqlServer(0, metrics, dispatcher);
            resp.start();
            sql.start();
            byte[] get = request("GET", "hot");
            pipeline = new byte[get.length * pipelineSize];
            for (int index = 0; index < pipelineSize; index++) {
                System.arraycopy(
                        get, 0, pipeline, index * get.length, get.length);
            }
        }

        @TearDown
        public void tearDown() throws IOException {
            IOException failure = null;
            try {
                sql.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                resp.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            if (failure != null) throw failure;
        }
    }

    @State(Scope.Thread)
    public static class ClientState {
        private Socket resp;
        private Socket sql;

        Socket resp(EndpointState endpoint) throws IOException {
            if (resp == null) {
                resp = connect(endpoint.resp.port());
            }
            return resp;
        }

        Socket sql(EndpointState endpoint) throws IOException {
            if (sql == null) {
                sql = connect(endpoint.sql.port());
            }
            return sql;
        }

        @TearDown
        public void tearDown() throws IOException {
            if (resp != null) resp.close();
            if (sql != null) sql.close();
        }

        private static Socket connect(int port) throws IOException {
            Socket socket = new Socket("127.0.0.1", port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5_000);
            return socket;
        }
    }

    @Benchmark
    public int respPing(EndpointState endpoint, ClientState client)
            throws IOException {
        return exchange(client.resp(endpoint), request("PING"), 7);
    }

    @Benchmark
    public int respGet(EndpointState endpoint, ClientState client)
            throws IOException {
        return exchange(client.resp(endpoint), request("GET", "hot"), 22);
    }

    @Benchmark
    public int respSet(EndpointState endpoint, ClientState client)
            throws IOException {
        return exchange(
                client.resp(endpoint),
                request("SET", "hot", "benchmark-value"),
                5);
    }

    @Benchmark
    public int respPipeline(EndpointState endpoint, ClientState client)
            throws IOException {
        return exchange(
                client.resp(endpoint),
                endpoint.pipeline,
                22 * endpoint.pipelineSize);
    }

    @Benchmark
    @Threads(2)
    public int respPipeline2Clients(
            EndpointState endpoint, ClientState client)
            throws IOException {
        return respPipeline(endpoint, client);
    }

    @Benchmark
    @Threads(4)
    public int respPipeline4Clients(
            EndpointState endpoint, ClientState client)
            throws IOException {
        return respPipeline(endpoint, client);
    }

    @Benchmark
    @Threads(8)
    public int respPipeline8Clients(
            EndpointState endpoint, ClientState client)
            throws IOException {
        return respPipeline(endpoint, client);
    }

    @Benchmark
    public int sqlCurrent(EndpointState endpoint, ClientState client)
            throws IOException {
        return exchange(
                client.sql(endpoint),
                "SELECT value FROM tempokv WHERE key = 'hot';"
                        .getBytes(StandardCharsets.UTF_8),
                23);
    }

    @Benchmark
    public int sqlHistorical(EndpointState endpoint, ClientState client)
            throws IOException {
        return exchange(
                client.sql(endpoint),
                "SELECT value FROM tempokv AS OF VERSION 1 "
                        .concat("WHERE key = 'hot';")
                        .getBytes(StandardCharsets.UTF_8),
                23);
    }

    private static int exchange(
            Socket socket, byte[] request, int responseBytes)
            throws IOException {
        socket.getOutputStream().write(request);
        socket.getOutputStream().flush();
        byte[] response =
                socket.getInputStream().readNBytes(responseBytes);
        if (response.length != responseBytes) {
            throw new IOException("Truncated benchmark response");
        }
        return response.length;
    }

    private static byte[] request(String... values) {
        StringBuilder request =
                new StringBuilder("*")
                        .append(values.length)
                        .append("\r\n");
        for (String value : values) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            request.append('$')
                    .append(encoded.length)
                    .append("\r\n")
                    .append(value)
                    .append("\r\n");
        }
        return request.toString().getBytes(StandardCharsets.UTF_8);
    }
}
