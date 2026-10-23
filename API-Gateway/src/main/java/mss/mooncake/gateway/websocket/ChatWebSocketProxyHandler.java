package mss.mooncake.gateway.websocket;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

public class ChatWebSocketProxyHandler implements WebSocketHandler, SubProtocolCapable {
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final int MAX_PENDING_BYTES = 128 * 1024;
    private final HttpClient client;
    private final URI upstreamUri;
    private final ConcurrentHashMap<String, Bridge> sessions = new ConcurrentHashMap<>();

    public ChatWebSocketProxyHandler(HttpClient client, URI upstreamUri) {
        this.client = client;
        this.upstreamUri = upstreamUri;
    }

    @Override public List<String> getSubProtocols() { return List.of("v12.stomp", "v11.stomp", "v10.stomp"); }
    @Override public boolean supportsPartialMessages() { return false; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
        session.setBinaryMessageSizeLimit(MAX_MESSAGE_BYTES);
        var bridge = new Bridge(new ConcurrentWebSocketSessionDecorator(session, 15000, MAX_PENDING_BYTES));
        sessions.put(session.getId(), bridge);
        var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        String protocol = session.getAcceptedProtocol();
        if (protocol != null && !protocol.isBlank()) builder.subprotocols(protocol);
        String origin = session.getHandshakeHeaders().getOrigin();
        if (origin != null) builder.header("Origin", origin);
        var handshake = builder.buildAsync(upstreamUri, bridge);
        try {
            // Complete the upstream handshake before accepting the client's CONNECT frame.
            bridge.upstream = handshake.get(6, TimeUnit.SECONDS);
        } catch (Exception ex) {
            handshake.cancel(true);
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            bridge.fail();
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        Bridge bridge = sessions.get(session.getId());
        if (bridge == null || bridge.closed.get() || bridge.upstream == null) return;
        if (message instanceof TextMessage text) {
            bridge.enqueue(text.getPayload().getBytes(StandardCharsets.UTF_8).length,
                    () -> bridge.upstream.sendText(text.getPayload(), text.isLast()));
        } else if (message instanceof BinaryMessage binary) {
            var data = copy(binary.getPayload());
            bridge.enqueue(data.remaining(), () -> bridge.upstream.sendBinary(data, binary.isLast()));
        } else if (message instanceof PongMessage pong) {
            var data = copy(pong.getPayload());
            bridge.enqueue(data.remaining(), () -> bridge.upstream.sendPong(data));
        } else if (message instanceof PingMessage ping) {
            var data = copy(ping.getPayload());
            bridge.enqueue(data.remaining(), () -> bridge.upstream.sendPing(data));
        }
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
        Bridge bridge = sessions.get(session.getId());
        if (bridge != null) bridge.fail();
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        Bridge bridge = sessions.remove(session.getId());
        if (bridge != null && bridge.closed.compareAndSet(false, true) && bridge.upstream != null) {
            bridge.upstream.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(error -> {
                bridge.upstream.abort();
                return null;
            });
        }
    }

    @PreDestroy public void shutdown() { sessions.values().forEach(Bridge::fail); }

    private static ByteBuffer copy(ByteBuffer source) {
        ByteBuffer result = ByteBuffer.allocate(source.remaining());
        result.put(source.duplicate()).flip();
        return result;
    }

    private class Bridge implements WebSocket.Listener {
        private final WebSocketSession downstream;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger pendingBytes = new AtomicInteger();
        private final AtomicInteger pendingFrames = new AtomicInteger();
        private CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);
        private volatile WebSocket upstream;
        private int receivedBytes;

        private Bridge(WebSocketSession downstream) { this.downstream = downstream; }

        private synchronized void enqueue(int bytes, Supplier<CompletableFuture<WebSocket>> write) {
            if (closed.get()) return;
            if (bytes > MAX_MESSAGE_BYTES || pendingBytes.addAndGet(bytes) > MAX_PENDING_BYTES
                    || pendingFrames.incrementAndGet() > 128) {
                fail();
                return;
            }
            // JDK WebSocket permits a single pending text/binary write; chain writes and bound their total size.
            writes = writes.thenCompose(ignored -> write.get().thenApply(socket -> (Void) null));
            writes.whenComplete((ignored, error) -> {
                pendingBytes.addAndGet(-bytes);
                pendingFrames.decrementAndGet();
                if (error != null) fail();
            });
        }

        @Override public void onOpen(WebSocket socket) {
            if (closed.get()) {
                socket.abort();
                return;
            }
            upstream = socket;
            socket.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (accept(data.toString().getBytes(StandardCharsets.UTF_8).length, last)) {
                send(new TextMessage(data.toString(), last));
                socket.request(1);
            }
            return null;
        }

        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            if (accept(data.remaining(), last)) {
                send(new BinaryMessage(copy(data), last));
                socket.request(1);
            }
            return null;
        }

        private boolean accept(int bytes, boolean last) {
            receivedBytes += bytes;
            if (receivedBytes > MAX_MESSAGE_BYTES) {
                fail();
                return false;
            }
            if (last) receivedBytes = 0;
            return !closed.get();
        }

        @Override public CompletionStage<?> onPing(WebSocket socket, ByteBuffer data) {
            send(new PingMessage(copy(data)));
            socket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onPong(WebSocket socket, ByteBuffer data) {
            send(new PongMessage(copy(data)));
            socket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            if (closed.compareAndSet(false, true)) {
                sessions.remove(downstream.getId());
                try { downstream.close(new CloseStatus(statusCode, reason)); } catch (IOException ignored) { }
            }
            return socket.sendClose(WebSocket.NORMAL_CLOSURE, "");
        }

        @Override public void onError(WebSocket socket, Throwable error) { fail(); }

        private void send(WebSocketMessage<?> message) {
            if (closed.get()) return;
            try { downstream.sendMessage(message); } catch (IOException | RuntimeException ex) { fail(); }
        }

        private void fail() {
            if (!closed.compareAndSet(false, true)) return;
            sessions.remove(downstream.getId());
            if (upstream != null) upstream.abort();
            try { downstream.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { }
        }
    }
}
