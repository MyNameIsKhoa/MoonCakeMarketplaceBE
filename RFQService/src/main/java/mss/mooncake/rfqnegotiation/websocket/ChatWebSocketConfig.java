package mss.mooncake.rfqnegotiation.websocket;
import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
@Configuration
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final ChatStompInterceptor interceptor;
    private final String[] allowedOrigins;

    public ChatWebSocketConfig(ChatStompInterceptor interceptor,
            @Value("${chat.allowed-origins:http://localhost:5173,http://localhost:3000}") String allowedOrigins) {
        this.interceptor = interceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isBlank()).toArray(String[]::new);
        if (this.allowedOrigins.length == 0 || Arrays.stream(this.allowedOrigins).anyMatch(origin -> {
            URI uri = URI.create(origin);
            return origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
        })) throw new IllegalArgumentException("chat.allowed-origins cần danh sách origin HTTP(S) cụ thể");
    }

    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat").setAllowedOrigins(allowedOrigins);
    }

    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor);
    }

    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024).setSendBufferSizeLimit(128 * 1024).setSendTimeLimit(15000);
    }
}
