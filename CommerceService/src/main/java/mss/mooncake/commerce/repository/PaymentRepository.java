package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> { }
