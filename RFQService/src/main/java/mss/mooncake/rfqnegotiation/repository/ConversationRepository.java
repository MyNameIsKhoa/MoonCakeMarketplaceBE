package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> { }
