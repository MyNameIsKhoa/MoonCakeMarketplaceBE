package mss.mooncake.identity.repository;

import java.util.Optional;
import mss.mooncake.identity.entity.PayoutAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccountEntity, Long> {
    Optional<PayoutAccountEntity> findByUserIdAndOwnerKind(Long userId, String ownerKind);
}
