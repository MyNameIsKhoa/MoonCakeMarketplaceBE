package mss.mooncake.catalogstore.repository;

import mss.mooncake.catalogstore.entity.StoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<StoreEntity, Long> { }
