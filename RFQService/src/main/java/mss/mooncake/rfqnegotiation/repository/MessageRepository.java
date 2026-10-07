package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MessageRepository extends JpaRepository<MessageEntity, Long> { }
