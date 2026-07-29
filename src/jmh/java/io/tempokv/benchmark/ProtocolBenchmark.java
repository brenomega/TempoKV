package io.tempokv.benchmark;

import io.tempokv.application.Command;
import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandHandler;
import io.tempokv.application.CommandResult;
import io.tempokv.application.CommandValidator;
import io.tempokv.application.KeyValueCommand;
import io.tempokv.observability.CommandTracer;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.protocol.sql.PlanExecutor;
import io.tempokv.protocol.sql.SqlCompiler;
import io.tempokv.protocol.sql.SqlResultEncoder;
import io.tempokv.security.AccessController;
import io.tempokv.security.Authenticator;
import io.tempokv.server.RespConnectionHandler;
import io.tempokv.server.Session;
import io.tempokv.server.SqlConnectionHandler;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Thread)
public class ProtocolBenchmark {
    @Param({"1", "16", "128"})
    public int pipelineSize;

    private RespConnectionHandler resp;
    private SqlConnectionHandler sql;
    private SqlCompiler compiler;
    private byte[] pipeline;
    private byte[] sqlCurrent;

    @Setup
    public void setup() {
        MetricsRegistry metrics = new MetricsRegistry();
        CommandHandler<Command> handler = new CommandHandler<>() {
            @Override
            public Class<Command> commandType() {
                return Command.class;
            }

            @Override
            public CommandResult handle(Command command, Session session) {
                if (command instanceof KeyValueCommand keyValue
                        && keyValue.kind() == KeyValueCommand.Kind.GET) {
                    return new CommandResult.BulkString(
                            "benchmark-value".getBytes(StandardCharsets.UTF_8));
                }
                return CommandResult.simpleString(
                        command.name().equals("PING") ? "PONG" : "OK");
            }
        };
        CommandDispatcher dispatcher = new CommandDispatcher(
                new CommandValidator(),
                List.of(handler),
                new CommandTracer(metrics));
        resp = new RespConnectionHandler(
                Authenticator.permissive(),
                AccessController.permissive(),
                dispatcher,
                metrics);
        compiler = new SqlCompiler();
        sql = new SqlConnectionHandler(
                Authenticator.permissive(),
                compiler,
                new PlanExecutor(dispatcher, AccessController.permissive()),
                new SqlResultEncoder(),
                metrics);
        byte[] request = "*2\r\n$3\r\nGET\r\n$3\r\nhot\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        pipeline = new byte[request.length * pipelineSize];
        for (int index = 0; index < pipelineSize; index++) {
            System.arraycopy(request, 0, pipeline, index * request.length, request.length);
        }
        sqlCurrent =
                "SELECT value FROM tempokv WHERE key = 'hot';"
                        .getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public void respGetPipeline(Blackhole blackhole) {
        resp.onBytes(pipeline, blackhole::consume);
    }

    @Benchmark
    public Object sqlParsePlanCurrent() {
        return compiler.compile("SELECT value FROM tempokv WHERE key = 'hot';");
    }

    @Benchmark
    public Object sqlParsePlanHistorical() {
        return compiler.compile(
                "SELECT value FROM tempokv AS OF VERSION 1 WHERE key = 'hot';");
    }

    @Benchmark
    public void sqlCurrentPath(Blackhole blackhole) {
        sql.onBytes(sqlCurrent, blackhole::consume);
    }
}
