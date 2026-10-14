package mss.mooncake.identity.repository;

import java.util.List;
import mss.mooncake.identity.entity.SupplierVerificationAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierVerificationAuditRepository extends JpaRepository<SupplierVerificationAuditEntity, Long> {
    List<SupplierVerificationAuditEntity> findBySupplierIdOrderByActedAtDescIdDesc(Long supplierId);
}
