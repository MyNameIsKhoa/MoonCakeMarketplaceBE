package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RfqRepository extends JpaRepository<RfqEntity, Long> { }
