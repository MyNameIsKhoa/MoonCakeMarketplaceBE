package mss.mooncake.catalogstore.repository;

import mss.mooncake.catalogstore.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, Long> { }
