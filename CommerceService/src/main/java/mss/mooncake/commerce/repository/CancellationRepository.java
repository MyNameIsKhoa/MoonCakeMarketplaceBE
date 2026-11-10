package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.CancellationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;

public interface CancellationRepository extends JpaRepository<CancellationEntity, Long> {
    @Query("select distinct c.sellerOrderId from CancellationEntity c where c.sellerOrderId in :ids")
    List<Long> sellerOrderIdsWithCancellation(Collection<Long> ids);

    @Query("select c.sellerOrderId from CancellationEntity c where c.sellerOrderId in :ids and c.status = 'REQUESTED'")
    List<Long> pendingSellerOrderIds(Collection<Long> ids);
}
