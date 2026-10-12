package mss.mooncake.identity.repository;

import java.util.Optional;
import mss.mooncake.identity.entity.SupplierProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierProfileRepository extends JpaRepository<SupplierProfileEntity, Long> {
    Optional<SupplierProfileEntity> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
    boolean existsByTaxCode(String taxCode);
    Optional<SupplierProfileEntity> findByTaxCode(String taxCode);
}
