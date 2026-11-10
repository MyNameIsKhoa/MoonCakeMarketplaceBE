package mss.mooncake.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import mss.mooncake.identity.dto.request.CreateNotificationRequest;
import mss.mooncake.identity.entity.NotificationEntity;
import mss.mooncake.identity.repository.CustomerProfileRepository;
import mss.mooncake.identity.repository.NotificationRepository;
import mss.mooncake.identity.repository.SupplierProfileRepository;
import mss.mooncake.identity.repository.UserRepository;
import mss.mooncake.identity.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock NotificationRepository notifications;
    @Mock UserRepository users;
    @Mock CustomerProfileRepository customers;
    @Mock SupplierProfileRepository suppliers;
    NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(notifications, users, customers, suppliers, JsonMapper.builder().build());
    }

    private static CreateNotificationRequest request(String dedupKey) {
        return new CreateNotificationRequest(7L, null, null, "CHAT_MESSAGE", "Tin nhắn mới", "Xin chào",
                Map.of("kind", "CONVERSATION", "id", "9"), dedupKey);
    }

    @Test
    void concurrentDuplicateIsAnsweredWithTheWinningRow() {
        var winner = new NotificationEntity(7L, "CHAT_MESSAGE", "Tin nhắn mới", "Xin chào", null, "CHAT:9:0",
                LocalDateTime.of(2026, 11, 8, 10, 0));
        ReflectionTestUtils.setField(winner, "id", 55L);
        when(users.existsById(7L)).thenReturn(true);
        when(notifications.findByUserIdRefAndDedupKey(7L, "CHAT:9:0"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(notifications.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("UQ_notification_user_dedup"));

        var result = service.create(request("CHAT:9:0"));

        assertThat(result.notificationId()).isEqualTo(55L);
        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.created()).isFalse();
    }

    @Test
    void withoutDedupKeyEveryCallCreatesARow() {
        when(users.existsById(7L)).thenReturn(true);
        when(notifications.saveAndFlush(any())).thenAnswer(invocation -> {
            NotificationEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 56L);
            return saved;
        });

        var result = service.create(request(null));

        assertThat(result.created()).isTrue();
        assertThat(result.notificationId()).isEqualTo(56L);
        verify(notifications, never()).findByUserIdRefAndDedupKey(any(), any());
    }
}
