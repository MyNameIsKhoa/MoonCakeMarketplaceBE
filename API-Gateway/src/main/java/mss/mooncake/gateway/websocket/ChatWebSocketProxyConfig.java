package mss.mooncake.gateway.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** A raw WebSocket bridge for the existing servlet Gateway; RFQ authenticates STOMP CONNECT. */
@Configuration
@EnableWebSocket
public class ChatWebSocketProxyConfig implements WebSocketConfigurer {
    private final ChatWebSocketProxyHandler handler;
    private final String[] origins;

    public ChatWebSocketProxyConfig(ChatWebSocketProxyHandler handler,
            @Value("${chat.allowed-origins:${app.cors.allowed-origins:http://localhost:5173}}") String allowedOrigins) {
        this.handler = handler;
        this.origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(value -> !value.isBlank()).toArray(String[]::new);
        if (origins.length == 0 || Arrays.stream(origins).anyMatch(origin -> {
            URI uri = URI.create(origin);
            return origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
        })) throw new IllegalArgumentException("chat.allowed-origins cần origin HTTP(S) cụ thể");
    }

    @Bean(destroyMethod = "close")
    static HttpClient chatProxyHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    static ChatWebSocketProxyHandler chatWebSocketProxyHandler(HttpClient chatProxyHttpClient,
            @Value("${services.rfq.base-url:${RFQ_SERVICE_URL:http://localhost:8084}}") String baseUrl) {
        URI base = URI.create(baseUrl);
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))) {
            throw new IllegalArgumentException("RFQ base URL cần HTTP(S) URL hợp lệ");
        }
        URI upstream = URI.create(("https".equals(base.getScheme()) ? "wss" : "ws")
                + "://" + base.getRawAuthority() + "/ws/chat");
        return new ChatWebSocketProxyHandler(chatProxyHttpClient, upstream);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat").setAllowedOrigins(origins).addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler webSocketHandler, Map<String, Object> attributes) {
                if (request.getURI().getRawQuery() != null) {
                    response.setStatusCode(HttpStatus.BAD_REQUEST);
                    return false;
                }
                return true;
            }
            @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler webSocketHandler, Exception exception) { }
        });
    }
}
