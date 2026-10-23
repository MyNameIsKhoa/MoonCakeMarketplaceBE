/*
 * Java 21 source-file smoke test. Use newly created fixture accounts only; no real users.
 * Process environment (never pass bearer tokens on the command line):
 * TV4_FIXTURE_ACCOUNTS=true, TV4_BUYER_TOKEN, TV4_SUPPLIER_TOKEN, TV4_CONVERSATION_ID
 * Optional: TV4_BASE_URL=http://localhost:8080, TV4_ORIGIN=http://localhost:5173
 * Run: java scripts/SmokeTv4Chat.java
 * Requires the conversation created by smoke-tv4-http.ps1 between the same two fresh fixtures.
 */
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class SmokeTv4Chat {
    private static final Pattern MESSAGE_ID = Pattern.compile("\\\"messageId\\\"\\s*:\\s*\\\"([0-9]+)\\\"");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public static void main(String[] args) throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("TV4_FIXTURE_ACCOUNTS"))) {
            throw new IllegalArgumentException("Use only newly created fixture accounts; set TV4_FIXTURE_ACCOUNTS=true");
        }
        String buyerToken = required("TV4_BUYER_TOKEN");
        String supplierToken = required("TV4_SUPPLIER_TOKEN");
        String conversationId = required("TV4_CONVERSATION_ID");
        if (!conversationId.matches("[1-9][0-9]*")) throw new IllegalArgumentException("Invalid fixture conversation ID");
        String baseUrl = value("TV4_BASE_URL", "http://localhost:8080").replaceAll("/$", "");
        URI base = URI.create(baseUrl);
        if (!java.util.Set.of("http", "https").contains(base.getScheme()) || base.getRawQuery() != null || base.getUserInfo() != null) {
            throw new IllegalArgumentException("TV4_BASE_URL must be an HTTP(S) origin without credentials/query");
        }
        URI wsUri = URI.create((base.getScheme().equals("https") ? "wss" : "ws") + "://" + base.getRawAuthority() + "/ws/chat");
        String origin = value("TV4_ORIGIN", "http://localhost:5173");
        String clientMessageId = "smoke-" + UUID.randomUUID();
        String content = "TV4 fixture chat " + UUID.randomUUID();
        String payload = "{\"clientMessageId\":\"" + clientMessageId + "\",\"messageType\":\"TEXT\",\"content\":\"" + content + "\"}";
        String destination = "/app/conversations/" + conversationId + "/messages";

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            Channel buyer = null;
            Channel supplier = null;
            try {
                buyer = connect(client, wsUri, origin, buyerToken);
                supplier = connect(client, wsUri, origin, supplierToken);
                buyer.send("SUBSCRIBE\nid:buyer-smoke\ndestination:/topic/conversations/" + conversationId + "\n\n\0");
                supplier.send("SUBSCRIBE\nid:supplier-smoke\ndestination:/topic/conversations/" + conversationId + "\n\n\0");
                // The in-memory broker has no subscription receipt; allow it to install the two subscriptions.
                Thread.sleep(300);
                String send = "SEND\ndestination:" + destination + "\ncontent-type:application/json\ncontent-length:"
                        + payload.getBytes(StandardCharsets.UTF_8).length + "\n\n" + payload + "\0";
                buyer.send(send);
                String first = buyer.next("MESSAGE");
                String supplierFirst = supplier.next("MESSAGE");
                String messageId = messageId(first);
                require(messageId.equals(messageId(supplierFirst)), "buyer and supplier receive the same persisted message");
                require(first.contains(clientMessageId) && first.contains(content), "broadcast retains correlation ID and body");
                buyer.send(send);
                require(messageId.equals(messageId(buyer.next("MESSAGE"))), "retry echoes the existing message ID");
                require(messageId.equals(messageId(supplier.next("MESSAGE"))), "supplier retry broadcast keeps the same ID");

                var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/conversations/" + conversationId + "/messages"))
                        .timeout(TIMEOUT).header("Authorization", "Bearer " + buyerToken).header("Accept", "application/json").GET().build();
                var history = client.send(request, HttpResponse.BodyHandlers.ofString());
                require(history.statusCode() == 200, "history API returns success");
                var ids = MESSAGE_ID.matcher(history.body());
                int occurrences = 0;
                while (ids.find()) if (messageId.equals(ids.group(1))) occurrences++;
                require(occurrences == 1 && history.body().contains(clientMessageId), "history contains exactly one durable message after replay");
                System.out.println("PASS: gateway CONNECT/SUBSCRIBE/SEND, both participant broadcasts, persistent retry dedup and REST history");
                System.out.println("Fixture conversationId=" + conversationId + ", messageId=" + messageId);
            } finally {
                if (buyer != null) buyer.close();
                if (supplier != null) supplier.close();
            }
        }
    }

    private static Channel connect(HttpClient client, URI uri, String origin, String token) throws Exception {
        Channel channel = new Channel();
        channel.socket = client.newWebSocketBuilder().connectTimeout(TIMEOUT).header("Origin", origin).subprotocols("v12.stomp")
                .buildAsync(uri, channel).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        try {
            channel.send("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\nAuthorization:Bearer " + token + "\n\n\0");
            channel.next("CONNECTED");
            return channel;
        } catch (Exception ex) {
            channel.close();
            throw ex;
        }
    }

    private static String messageId(String frame) {
        var matcher = MESSAGE_ID.matcher(frame);
        require(matcher.find(), "MESSAGE includes a string messageId");
        return matcher.group(1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("TV4 chat smoke failed: " + message);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing process environment " + name);
        return value;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static class Channel implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();
        private WebSocket socket;

        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            int end;
            while ((end = buffer.indexOf("\0")) >= 0) {
                String frame = buffer.substring(0, end).stripLeading();
                buffer.delete(0, end + 1);
                if (!frame.isBlank()) frames.add(frame);
            }
            ws.request(1);
            return null;
        }
        @Override public void onError(WebSocket ws, Throwable error) { frames.add("ERROR\ntransport failure"); }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            frames.add("ERROR\nconnection closed with code " + code);
            return null;
        }
        void send(String frame) { socket.sendText(frame, true).join(); }
        String next(String command) throws Exception {
            String frame = frames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            // Do not print protocol ERROR bodies: they may contain request/header information.
            require(frame != null && frame.startsWith(command + "\n"), "expected STOMP " + command + " frame");
            return frame;
        }
        void close() {
            if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "fixture done").exceptionally(error -> null);
        }
    }
}
