package mss.mooncake.identity.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import mss.mooncake.identity.entity.SupplierVerificationAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierVerificationAuditRepository extends JpaRepository<SupplierVerificationAuditEntity, Long> {
    List<SupplierVerificationAuditEntity> findBySupplierIdOrderByActedAtDescIdDesc(Long supplierId);

    /** Quyết định gần nhất có trạng thái mới thuộc {@code newStatuses} (dùng để lấy lý do duyệt/từ chối mới nhất). */
    Optional<SupplierVerificationAuditEntity> findFirstBySupplierIdAndNewStatusInOrderByActedAtDescIdDesc(
            Long supplierId, Collection<String> newStatuses);
}
