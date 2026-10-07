package mss.mooncake.catalogstore.repository;

import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductVariantRepository extends JpaRepository<ProductVariantEntity, Long> { }
