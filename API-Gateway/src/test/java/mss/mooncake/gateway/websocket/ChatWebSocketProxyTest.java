package mss.mooncake.gateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import mss.mooncake.gateway.config.SecurityConfig;
import mss.mooncake.gateway.security.JwtAuthenticationFilter;
import mss.mooncake.gateway.security.JwtTokenProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.SubProtocolCapable;

class ChatWebSocketProxyTest {
    private static ConfigurableApplicationContext backend;
    private static ConfigurableApplicationContext gateway;
    private static URI gatewayUri;
    private static final String ORIGIN = "http://localhost:5173";

    @Configuration
    @EnableAutoConfiguration
    @Import({ChatWebSocketProxyConfig.class, SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationFilter.class})
    static class GatewayConfig { }

    @Configuration
    @EnableAutoConfiguration
    @EnableWebSocket
    static class BackendConfig implements WebSocketConfigurer {
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(new StompTestBackend(), "/ws/chat").setAllowedOrigins(ORIGIN);
        }
        @Bean SecurityFilterChain allowHandshake(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }

    static class StompTestBackend extends TextWebSocketHandler implements SubProtocolCapable {
        @Override public List<String> getSubProtocols() { return List.of("v12.stomp"); }
        @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            if (message.getPayload().startsWith("CONNECT\n")) {
                session.sendMessage(new TextMessage("CONNECTED\nversion:1.2\n\n\0"));
            } else if (message.getPayload().startsWith("SUBSCRIBE\n")) {
                session.sendMessage(new TextMessage("MESSAGE\ndestination:/topic/conversations/7\nsubscription:sub-7\n"
                        + "message-id:1\n\n{\"content\":\"Hello through gateway\"}\0"));
            }
        }
    }

    @BeforeAll
    static void startServers() {
        backend = new SpringApplicationBuilder(BackendConfig.class).run("--server.port=0",
                "--spring.cloud.compatibility-verifier.enabled=false", "--spring.cloud.gateway.server.webmvc.routes[0].id=unused",
                "--spring.cloud.gateway.server.webmvc.routes[0].uri=http://localhost:1",
                "--spring.cloud.gateway.server.webmvc.routes[0].predicates[0]=Path=/unused");
        int backendPort = backend.getEnvironment().getProperty("local.server.port", Integer.class);
        gateway = new SpringApplicationBuilder(GatewayConfig.class).run("--server.port=0",
                "--services.rfq.base-url=http://localhost:" + backendPort,
                "--chat.allowed-origins=" + ORIGIN, "--app.cors.allowed-origins=" + ORIGIN,
                "--security.jwt.secret=test-gateway-secret-at-least-thirty-two-bytes",
                "--security.jwt.issuer=issuer", "--security.jwt.audience=audience",
                "--spring.cloud.compatibility-verifier.enabled=false");
        int gatewayPort = gateway.getEnvironment().getProperty("local.server.port", Integer.class);
        gatewayUri = URI.create("ws://localhost:" + gatewayPort + "/ws/chat");
    }

    @AfterAll static void stopServers() {
        if (gateway != null) gateway.close();
        if (backend != null) backend.close();
    }

    @Test
    void actualHandshakeConnectAndSubscribeFramesPassThroughGateway() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        try (var client = HttpClient.newHttpClient()) {
            var ws = client.newWebSocketBuilder().header("Origin", ORIGIN).subprotocols("v12.stomp")
                    .buildAsync(gatewayUri, new WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder();
                        @Override public void onOpen(WebSocket socket) { socket.request(1); }
                        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) { received.add(buffer.toString()); buffer.setLength(0); }
                            socket.request(1);
                            return null;
                        }
                    }).get(10, TimeUnit.SECONDS);
            assertThat(ws.getSubprotocol()).isEqualTo("v12.stomp");
            ws.sendText("CONNECT\naccept-version:1.2\nAuthorization:Bearer test-only\n\n\0", true).join();
            assertThat(received.poll(10, TimeUnit.SECONDS)).startsWith("CONNECTED\n");
            ws.sendText("SUBSCRIBE\nid:sub-7\ndestination:/topic/conversations/7\n\n\0", true).join();
            assertThat(received.poll(10, TimeUnit.SECONDS)).contains("Hello through gateway", "destination:/topic/conversations/7");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void handshakeRejectsUnlistedOriginsAndTokensInQuery() {
        try (var client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> client.newWebSocketBuilder().header("Origin", "http://untrusted.example")
                    .buildAsync(gatewayUri, new WebSocket.Listener() { }).join()).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> client.newWebSocketBuilder().header("Origin", ORIGIN)
                    .buildAsync(URI.create(gatewayUri + "?access_token=secret"), new WebSocket.Listener() { }).join())
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
