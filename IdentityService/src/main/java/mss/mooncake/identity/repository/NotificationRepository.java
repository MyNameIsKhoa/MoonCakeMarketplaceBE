package mss.mooncake.identity.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import mss.mooncake.identity.entity.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {
    Page<NotificationEntity> findByUserIdRef(Long userIdRef, Pageable pageable);

    long countByUserIdRefAndReadAtIsNull(Long userIdRef);

    Optional<NotificationEntity> findByIdAndUserIdRef(Long id, Long userIdRef);

    Optional<NotificationEntity> findByUserIdRefAndDedupKey(Long userIdRef, String dedupKey);

    @Modifying
    @Query("update NotificationEntity n set n.readAt = :now where n.userIdRef = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
