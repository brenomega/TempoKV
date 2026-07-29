package io.tempokv.server;

import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandResult;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.protocol.resp.RespCommandMapper;
import io.tempokv.protocol.resp.RespDecoder;
import io.tempokv.protocol.resp.RespEncoder;
import io.tempokv.protocol.resp.RespFrame;
import io.tempokv.security.AccessController;
import io.tempokv.security.Authenticator;
import io.tempokv.transaction.CommitFailedException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/** Connects RESP decoding and encoding to the application pipeline for one session. */
public final class RespConnectionHandler implements ClientConnection.ConnectionProcessor {
    private final Session session = new Session();
    private final Authenticator authenticator;
    private final AccessController accessController;
    private final CommandDispatcher dispatcher;
    private final MetricsRegistry metrics;
    private final RespDecoder decoder = new RespDecoder();
    private final RespCommandMapper mapper = new RespCommandMapper();
    private final RespEncoder encoder = new RespEncoder();

    /** Creates a handler and authenticates its newly allocated session. */
    public RespConnectionHandler(Authenticator authenticator, AccessController accessController, CommandDispatcher dispatcher, MetricsRegistry metrics) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator"); this.accessController = Objects.requireNonNull(accessController, "accessController"); this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher"); this.metrics = Objects.requireNonNull(metrics, "metrics"); this.authenticator.authenticate(session);
    }

    /** Decodes all complete requests in a chunk and emits their responses in order. */
    @Override public void onBytes(byte[] bytes, Consumer<byte[]> responses) {
        try {
            for (RespFrame frame : decoder.feed(bytes)) {
                long started = System.nanoTime(); CommandResult result;
                try {
                    var credentials = mapper.credentials(frame);
                    if (credentials.isPresent()) {
                        var supplied = credentials.orElseThrow();
                        result = authenticator.authenticate(
                                session,
                                supplied.username(),
                                supplied.password())
                                ? CommandResult.simpleString("OK")
                                : CommandResult.error(
                                        "ERR invalid credentials");
                    } else {
                        var command = mapper.map(frame);
                        result = accessController.isAllowed(session, command)
                                ? dispatcher.dispatch(command, session)
                                : CommandResult.error(accessController.denialMessage(
                                        session, command));
                    }
                } catch (RespCommandMapper.CommandMappingException | IllegalArgumentException | CommitFailedException exception) {
                    result = CommandResult.error(exception.getMessage());
                }
                metrics.recordLatency("commands.latency", Duration.ofNanos(System.nanoTime() - started));
                responses.accept(encoder.encode(result));
            }
        } catch (RespDecoder.ProtocolException exception) { responses.accept(encoder.encode(CommandResult.error(exception.getMessage()))); }
    }

    /** Aborts and releases an active transaction if the RESP connection is lost. */
    @Override public void onClose() {
        session.close();
    }
}
