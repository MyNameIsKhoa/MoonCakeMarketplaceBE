package mss.mooncake.catalogstore.repository;

import mss.mooncake.catalogstore.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<CategoryEntity, Long> { }
