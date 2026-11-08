package mss.mooncake.identity.repository;

import java.util.Optional;
import mss.mooncake.identity.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import mss.mooncake.identity.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);
    boolean existsByEmail(String email);
    Page<UserEntity> findByEmailContainingIgnoreCaseAndStatus(String email, UserStatus status, Pageable pageable);
    Page<UserEntity> findByEmailContainingIgnoreCase(String email, Pageable pageable);
    Page<UserEntity> findByStatus(UserStatus status, Pageable pageable);
}
