package mss.mooncake.identity.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import mss.mooncake.identity.dto.request.CreateNotificationRequest;
import mss.mooncake.identity.dto.response.InternalNotificationResponse;
import mss.mooncake.identity.dto.response.NotificationPageResponse;
import mss.mooncake.identity.dto.response.NotificationResponse;
import mss.mooncake.identity.entity.CustomerProfileEntity;
import mss.mooncake.identity.entity.NotificationEntity;
import mss.mooncake.identity.entity.SupplierProfileEntity;
import mss.mooncake.identity.exception.ResourceNotFoundException;
import mss.mooncake.identity.repository.CustomerProfileRepository;
import mss.mooncake.identity.repository.NotificationRepository;
import mss.mooncake.identity.repository.SupplierProfileRepository;
import mss.mooncake.identity.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** In-app notifications. Never sends email: other services only write rows here, users read their own. */
@Service
public class NotificationService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_TARGET_FIELDS = 10;
    private static final int MAX_TARGET_JSON = 1000;
    private static final Pattern TARGET_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,39}");
    private static final Pattern TARGET_KIND = Pattern.compile("[A-Z][A-Z_]{0,29}");
    private static final TypeReference<Map<String, Object>> TARGET_TYPE = new TypeReference<>() { };

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final CustomerProfileRepository customers;
    private final SupplierProfileRepository suppliers;
    private final ObjectMapper json;

    public NotificationService(NotificationRepository notifications, UserRepository users,
                               CustomerProfileRepository customers, SupplierProfileRepository suppliers,
                               ObjectMapper json) {
        this.notifications = notifications;
        this.users = users;
        this.customers = customers;
        this.suppliers = suppliers;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public NotificationPageResponse list(Long userId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page phải >= 0 và size từ 1 đến " + MAX_PAGE_SIZE + ".");
        }
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        Page<NotificationEntity> result = notifications.findByUserIdRef(userId, PageRequest.of(page, size, newestFirst));
        return new NotificationPageResponse(result.map(this::response).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByUserIdRefAndReadAtIsNull(userId);
    }

    /** Another user's notification answers 404, the same as a missing one. Marking twice keeps the first readAt. */
    @Transactional
    public NotificationResponse markRead(Long userId, Long notificationId) {
        NotificationEntity notification = notifications.findByIdAndUserIdRef(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông báo."));
        notification.markRead(now());
        return response(notification);
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notifications.markAllRead(userId, now());
    }

    /**
     * Not one transaction on purpose: a concurrent duplicate fails on UQ_notification_user_dedup in its own
     * insert transaction and is then answered with the row that won.
     */
    public InternalNotificationResponse create(CreateNotificationRequest request) {
        Long userId = recipient(request);
        String targetJson = targetJson(request.target());
        String dedupKey = request.dedupKey();
        if (dedupKey != null) {
            var existing = notifications.findByUserIdRefAndDedupKey(userId, dedupKey);
            if (existing.isPresent()) return internal(existing.get(), false);
        }
        NotificationEntity notification = new NotificationEntity(userId, request.type(), request.title().strip(),
                blankToNull(request.body()), targetJson, dedupKey, now());
        try {
            return internal(notifications.saveAndFlush(notification), true);
        } catch (DataIntegrityViolationException duplicate) {
            if (dedupKey == null) throw duplicate;
            return notifications.findByUserIdRefAndDedupKey(userId, dedupKey)
                    .map(winner -> internal(winner, false))
                    .orElseThrow(() -> duplicate);
        }
    }

    private Long recipient(CreateNotificationRequest request) {
        long given = Stream.of(request.userId(), request.customerId(), request.supplierId()).filter(id -> id != null).count();
        if (given != 1) {
            throw new IllegalArgumentException("Cần đúng một người nhận: userId, customerId hoặc supplierId.");
        }
        if (request.userId() != null) {
            if (!users.existsById(request.userId())) throw new ResourceNotFoundException("Không tìm thấy người dùng nhận thông báo.");
            return request.userId();
        }
        if (request.customerId() != null) {
            return customers.findById(request.customerId()).map(CustomerProfileEntity::getUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy khách hàng nhận thông báo."));
        }
        return suppliers.findById(request.supplierId()).map(SupplierProfileEntity::getUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhà cung cấp nhận thông báo."));
    }

    /** A flat object of short scalars with an upper-case "kind", e.g. {"kind":"ORDER","id":"501"}. */
    private String targetJson(Map<String, Object> target) {
        if (target == null) return null;
        if (target.size() > MAX_TARGET_FIELDS || !(target.get("kind") instanceof String kind)
                || !TARGET_KIND.matcher(kind).matches()) {
            throw invalidTarget();
        }
        for (var field : target.entrySet()) {
            Object value = field.getValue();
            boolean scalar = value == null || value instanceof Number || value instanceof Boolean
                    || (value instanceof String text && text.length() <= 100);
            if (!TARGET_KEY.matcher(field.getKey()).matches() || !scalar) throw invalidTarget();
        }
        String serialized = json.writeValueAsString(target);
        if (serialized.length() > MAX_TARGET_JSON) throw invalidTarget();
        return serialized;
    }

    private static IllegalArgumentException invalidTarget() {
        return new IllegalArgumentException("target phải là object phẳng (tối đa " + MAX_TARGET_FIELDS
                + " trường, giá trị là chuỗi ngắn/số/boolean) và có \"kind\" viết hoa, ví dụ {\"kind\":\"ORDER\",\"id\":\"501\"}.");
    }

    private NotificationResponse response(NotificationEntity entity) {
        Map<String, Object> target = entity.getTargetJson() == null ? null : json.readValue(entity.getTargetJson(), TARGET_TYPE);
        return new NotificationResponse(entity.getId(), entity.getType(), entity.getTitle(), entity.getBody(), target,
                entity.getCreatedAt(), entity.getReadAt(), entity.getReadAt() != null);
    }

    private static InternalNotificationResponse internal(NotificationEntity entity, boolean created) {
        return new InternalNotificationResponse(entity.getId(), entity.getUserIdRef(), created);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }
}
