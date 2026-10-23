package mss.mooncake.rfqnegotiation.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.security.JwtTokenProvider;
import mss.mooncake.rfqnegotiation.security.UserPrincipal;
import mss.mooncake.rfqnegotiation.service.ConversationApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class ChatStompInterceptorTest {
    private static final String SECRET = "test-secret-for-chat-at-least-thirty-two-bytes";
    private final ConversationApplicationService conversations = mock(ConversationApplicationService.class);
    private final ChatStompInterceptor interceptor = new ChatStompInterceptor(
            new JwtTokenProvider(SECRET, "issuer", "audience"), conversations);

    @Test
    void connectRequiresJwtAndResolvesNumericUserIdRatherThanEmail() {
        var missing = StompHeaderAccessor.create(StompCommand.CONNECT);
        assertThatThrownBy(() -> interceptor.preSend(frame(missing), null)).isInstanceOf(RuntimeException.class);
        var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setNativeHeader("Authorization", "Bearer " + token());
        interceptor.preSend(frame(connect), null);
        assertThat(connect.getUser()).isInstanceOf(Authentication.class);
        assertThat(ChatStompInterceptor.userId(connect.getUser())).isEqualTo(101L);
        assertThat(connect.getUser().getName()).isEqualTo("101");
    }

    @Test
    void outsidersCannotSubscribeToAnotherConversation() {
        doThrow(ApiException.forbidden("NOT_CONVERSATION_PARTICIPANT", "Denied"))
                .when(conversations).assertParticipant(99L, 101L);
        var subscribe = authenticated(StompCommand.SUBSCRIBE, "/topic/conversations/99");
        assertThatThrownBy(() -> interceptor.preSend(frame(subscribe), null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void clientsCannotSendDirectlyToBrokerOrSubscribeWithWildcards() {
        assertThatThrownBy(() -> interceptor.preSend(frame(authenticated(StompCommand.SEND,
                "/topic/conversations/1")), null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(authenticated(StompCommand.SUBSCRIBE,
                "/topic/conversations/*")), null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(authenticated(StompCommand.SEND,
                "/app/anything")), null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void participantCanSubscribeAndSendThroughApprovedDestinations() {
        var subscribe = frame(authenticated(StompCommand.SUBSCRIBE, "/topic/conversations/7"));
        var send = frame(authenticated(StompCommand.SEND, "/app/conversations/7/messages"));
        assertThat(interceptor.preSend(subscribe, null)).isSameAs(subscribe);
        assertThat(interceptor.preSend(send, null)).isSameAs(send);
    }

    @Test
    void framesAfterTheConnectTokenExpiresRequireReconnect() {
        var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setNativeHeader("Authorization", "Bearer " + token());
        interceptor.preSend(frame(connect), null);
        assertThat(((Authentication) connect.getUser()).getDetails()).isInstanceOf(Instant.class);

        var expired = new UsernamePasswordAuthenticationToken(new UserPrincipal(101L, "buyer@test", List.of()), null, List.of());
        expired.setDetails(Instant.now().minusSeconds(1));
        var send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setDestination("/app/conversations/7/messages");
        send.setUser(expired);
        assertThatThrownBy(() -> interceptor.preSend(frame(send), null)).isInstanceOf(CredentialsExpiredException.class);
    }

    @Test
    void anonymousCannotSendOrSubscribe() {
        var subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setDestination("/topic/conversations/7");
        assertThatThrownBy(() -> interceptor.preSend(frame(subscribe), null)).isInstanceOf(RuntimeException.class);
    }

    private StompHeaderAccessor authenticated(StompCommand command, String destination) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        var principal = new UserPrincipal(101L, "buyer@test", List.of());
        accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return accessor;
    }

    private Message<byte[]> frame(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private String token() {
        return Jwts.builder().setSubject("buyer@test").setIssuer("issuer").setAudience("audience")
                .setExpiration(Date.from(Instant.now().plusSeconds(60))).claim("userId", 101L)
                .claim("roles", List.of("ROLE_CUSTOMER"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
