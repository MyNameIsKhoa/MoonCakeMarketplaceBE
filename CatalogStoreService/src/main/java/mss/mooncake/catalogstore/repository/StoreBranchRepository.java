package mss.mooncake.catalogstore.repository;

import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.entity.StoreBranchEntity;
import mss.mooncake.catalogstore.enums.StoreBranchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreBranchRepository extends JpaRepository<StoreBranchEntity, Long> {

    /** Chi nhánh chính lên đầu, sau đó theo thứ tự tạo. */
    List<StoreBranchEntity> findByStoreIdOrderByPrimaryDescIdAsc(Long storeId);

    List<StoreBranchEntity> findByStoreIdAndStatusOrderByPrimaryDescIdAsc(Long storeId, StoreBranchStatus status);

    Optional<StoreBranchEntity> findByIdAndStoreId(Long id, Long storeId);

    /**
     * Bỏ cờ chính của các chi nhánh khác. Chạy bulk update ngay (không chờ flush)
     * vì Hibernate flush INSERT trước UPDATE, sẽ vi phạm UQ_store_branch_primary.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StoreBranchEntity b set b.primary = false where b.storeId = :storeId and b.primary = true")
    int clearPrimary(@Param("storeId") Long storeId);
}
