package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named = "TEST_SQLSERVER_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${TEST_SQLSERVER_URL}",
        "spring.datasource.username=${TEST_SQLSERVER_USERNAME}",
        "spring.datasource.password=${TEST_SQLSERVER_PASSWORD}",
        "spring.datasource.driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class CatalogSnapshotSqlServerTest {
    @Autowired private CatalogApplicationService service;
    @Autowired private StoreRepository stores;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @Transactional
    void readsSnapshotFromPhysicalSchemaAndPersistsOnlyProductAuditColumns() {
        String suffix = UUID.randomUUID().toString();
        StoreEntity store = new StoreEntity();
        store.setSupplierIdRef(20L);
        store.setStoreCode("SNAPSHOT-" + suffix);
        store.setName("Snapshot SQL Server test");
        store.setSlug("snapshot-" + suffix);
        store.setStatus(StoreStatus.ACTIVE);
        store = stores.saveAndFlush(store);
        Long categoryId = jdbc.queryForObject(
                "INSERT INTO dbo.category (name, slug, status) OUTPUT INSERTED.category_id VALUES (?, ?, ?)",
                Long.class, "Test category", "category-" + suffix, "ACTIVE");
        ProductEntity product = new ProductEntity();
        product.setStoreId(store.getId());
        product.setCategoryId(categoryId);
        product.setProductCode("P-" + suffix);
        product.setName("Bánh trung thu");
        product.setStatus(ProductStatus.ACTIVE);
        product = products.saveAndFlush(product);
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProductId(product.getId());
        variant.setSku("SKU-" + suffix);
        variant.setVariantName("Đậu xanh");
        variant.setBasePrice(new BigDecimal("200000.00"));
        variant.setStockQuantity(100);
        variant.setStatus(ProductStatus.ACTIVE);
        variant = variants.saveAndFlush(variant);

        var snapshot = service.getProductSnapshot(product.getId(), variant.getId());

        assertThat(snapshot.storeId()).isEqualTo(store.getId());
        assertThat(snapshot.supplierId()).isEqualTo(20L);
        assertThat(snapshot.agreedUnitPrice()).isEqualByComparingTo("200000.00");
        assertThat(snapshot.status()).isEqualTo("ACTIVE");
        assertThat(product.getCreatedAt()).isBetween(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), LocalDateTime.now(ZoneOffset.UTC));
    }
}
