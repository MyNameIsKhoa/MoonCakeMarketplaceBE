package mss.mooncake.rfqnegotiation.websocket;

import io.jsonwebtoken.Claims;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import mss.mooncake.rfqnegotiation.security.JwtTokenProvider;
import mss.mooncake.rfqnegotiation.security.UserPrincipal;
import mss.mooncake.rfqnegotiation.service.ConversationApplicationService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class ChatStompInterceptor implements ChannelInterceptor {
    private static final Pattern SEND = Pattern.compile("/app/conversations/([1-9][0-9]*)/messages");
    private static final Pattern SUBSCRIBE = Pattern.compile("/topic/conversations/([1-9][0-9]*)");
    private final JwtTokenProvider tokens;
    private final ConversationApplicationService conversations;

    public ChatStompInterceptor(JwtTokenProvider tokens, ConversationApplicationService conversations) {
        this.tokens = tokens;
        this.conversations = conversations;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) throw new AccessDeniedException("Không nhận diện được STOMP frame");
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            authenticate(accessor);
            return message;
        }
        // DISCONNECT can arrive after authentication failure; let the transport clean up its session.
        if (command == StompCommand.DISCONNECT) return message;
        Long userId = userId(accessor.getUser());
        if (command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) {
            // The session keeps the CONNECT principal; a token that expired since then must reconnect.
            if (accessor.getUser() instanceof Authentication auth && auth.getDetails() instanceof Instant expiresAt
                    && !expiresAt.isAfter(Instant.now())) {
                throw new CredentialsExpiredException("Phiên chat đã hết hạn; hãy kết nối lại với token mới");
            }
            String destination = accessor.getDestination();
            var matcher = (command == StompCommand.SEND ? SEND : SUBSCRIBE)
                    .matcher(destination == null ? "" : destination);
            if (!matcher.matches()) throw new AccessDeniedException("Kênh chat không được phép");
            Long conversationId;
            try {
                conversationId = Long.valueOf(matcher.group(1));
            } catch (NumberFormatException ex) {
                throw new AccessDeniedException("Định danh hội thoại không hợp lệ");
            }
            conversations.assertParticipant(conversationId, userId);
        } else if (command != null && command != StompCommand.UNSUBSCRIBE
                && command != StompCommand.ACK && command != StompCommand.NACK) {
            throw new AccessDeniedException("STOMP command không được phép");
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null) authorization = accessor.getFirstNativeHeader("authorization");
        Claims claims = authorization != null && authorization.startsWith("Bearer ")
                ? tokens.parseClaims(authorization.substring(7)) : null;
        if (claims == null) throw new AuthenticationCredentialsNotFoundException("CONNECT cần Bearer JWT hợp lệ");
        Long userId;
        try {
            userId = Long.valueOf(claims.get("userId").toString());
            if (userId <= 0) throw new NumberFormatException();
        } catch (RuntimeException ex) {
            throw new AuthenticationCredentialsNotFoundException("JWT không có userId hợp lệ");
        }
        List<?> roles = claims.get("roles", List.class);
        var authorities = roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(SimpleGrantedAuthority::new).toList();
        var principal = new UserPrincipal(userId, claims.getSubject(), authorities);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(claims.getExpiration().toInstant());
        accessor.setUser(authentication);
    }

    public static Long userId(Principal principal) {
        if (principal instanceof Authentication auth && auth.isAuthenticated()
                && auth.getPrincipal() instanceof UserPrincipal user) return user.userId();
        throw new AuthenticationCredentialsNotFoundException("Phiên chat chưa được xác thực");
    }
}
