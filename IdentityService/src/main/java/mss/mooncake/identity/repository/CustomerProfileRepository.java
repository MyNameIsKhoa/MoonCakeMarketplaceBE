package mss.mooncake.identity.repository;

import java.util.Optional;
import mss.mooncake.identity.entity.CustomerProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfileEntity, Long> {
    Optional<CustomerProfileEntity> findByUserId(Long userId);
}
