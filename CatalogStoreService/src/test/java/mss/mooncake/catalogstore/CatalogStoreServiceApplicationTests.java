package mss.mooncake.catalogstore;

import jakarta.persistence.EntityManagerFactory;
import mss.mooncake.catalogstore.entity.CategoryEntity;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class CatalogStoreServiceApplicationTests {

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void contextLoads() {
    }

    @Test
    void onlyProductMapsAuditColumnsPresentInPhysicalCatalogSchema() {
        for (Class<?> type : new Class<?>[]{StoreEntity.class, CategoryEntity.class,
                ProductVariantEntity.class, PromotionEntity.class, PromotionRuleEntity.class}) {
            var attributes = entityManagerFactory.getMetamodel().entity(type).getAttributes();
            assertFalse(attributes.stream().anyMatch(attribute -> attribute.getName().equals("createdAt")));
            assertFalse(attributes.stream().anyMatch(attribute -> attribute.getName().equals("updatedAt")));
        }
        var productAttributes = entityManagerFactory.getMetamodel().entity(ProductEntity.class).getAttributes();
        assertTrue(productAttributes.stream().anyMatch(attribute -> attribute.getName().equals("createdAt")));
        assertTrue(productAttributes.stream().anyMatch(attribute -> attribute.getName().equals("updatedAt")));
    }

}
