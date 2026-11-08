package mss.mooncake.identity.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** In-app notification for one user. Times are UTC. */
@Entity
@Table(name = "notification")
@AttributeOverride(name = "id", column = @Column(name = "notification_id"))
public class NotificationEntity extends BaseIdentityEntity {
    @Column(name = "user_id_ref", nullable = false)
    private Long userIdRef;
    @Column(name = "notification_type", nullable = false, length = 50)
    private String type;
    @Column(name = "title", nullable = false, length = 255)
    private String title;
    @Column(name = "body", length = 1000)
    private String body;
    @Column(name = "target_json", length = 1000)
    private String targetJson;
    @Column(name = "dedup_key", length = 150)
    private String dedupKey;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected NotificationEntity() { }

    public NotificationEntity(Long userIdRef, String type, String title, String body, String targetJson,
                              String dedupKey, LocalDateTime createdAt) {
        this.userIdRef = userIdRef;
        this.type = type;
        this.title = title;
        this.body = body;
        this.targetJson = targetJson;
        this.dedupKey = dedupKey;
        this.createdAt = createdAt;
    }

    public Long getUserIdRef() { return userIdRef; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getTargetJson() { return targetJson; }
    public String getDedupKey() { return dedupKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getReadAt() { return readAt; }

    /** Keeps the first read time when marked again. */
    public void markRead(LocalDateTime now) {
        if (readAt == null) readAt = now;
    }
}
