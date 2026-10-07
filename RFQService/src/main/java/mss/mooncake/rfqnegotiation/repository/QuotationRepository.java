package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuotationRepository extends JpaRepository<QuotationEntity, Long> { }
