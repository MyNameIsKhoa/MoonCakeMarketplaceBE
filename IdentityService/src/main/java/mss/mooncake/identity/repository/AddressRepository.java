package mss.mooncake.identity.repository;

import mss.mooncake.identity.entity.AddressEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<AddressEntity, Long> { }
