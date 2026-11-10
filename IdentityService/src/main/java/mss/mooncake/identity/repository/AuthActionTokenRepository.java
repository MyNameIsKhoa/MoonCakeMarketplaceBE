package mss.mooncake.identity.repository;

import java.util.Optional;
import java.util.List;
import mss.mooncake.identity.entity.AuthActionTokenEntity;
import mss.mooncake.identity.enums.AuthTokenPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AuthActionTokenRepository extends JpaRepository<AuthActionTokenEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AuthActionTokenEntity t where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
    Optional<AuthActionTokenEntity> findUsableTokenByUserForUpdate(@Param("userId") Long userId,
                                                                 @Param("purpose") AuthTokenPurpose purpose);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AuthActionTokenEntity t where t.tokenHash = :tokenHash and t.purpose = :purpose and t.usedAt is null")
    Optional<AuthActionTokenEntity> findUsableTokenForUpdate(@Param("tokenHash") String tokenHash,
                                                              @Param("purpose") AuthTokenPurpose purpose);
    List<AuthActionTokenEntity> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(Long userId, AuthTokenPurpose purpose);
    void deleteByUserIdAndPurpose(Long userId, AuthTokenPurpose purpose);
}
