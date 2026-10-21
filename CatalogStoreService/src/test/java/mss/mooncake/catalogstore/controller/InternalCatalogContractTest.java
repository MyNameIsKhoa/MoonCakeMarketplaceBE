package mss.mooncake.catalogstore.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.enums.PromotionType;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.PromotionRepository;
import mss.mooncake.catalogstore.repository.PromotionRuleRepository;
import mss.mooncake.catalogstore.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract test IN06/IN07: chạy full context trên H2 với InternalServiceTokenFilter thật.
 * Kiểm tra tên field JSON mà Commerce/RFQ mirror, thứ tự/gộp ID, 404 và xác thực service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Internal catalog contract — IN06, IN07, IN08")
class InternalCatalogContractTest {

    // Khớp src/test/resources/application.yml
    private static final String COMMERCE_TOKEN = "test-commerce-catalog-token-32-bytes-minimum";
    private static final String RFQ_TOKEN = "test-rfq-catalog-token-32-bytes-minimum";

    @Autowired MockMvc mockMvc;
    @Autowired StoreRepository storeRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductVariantRepository variantRepository;

    private Long storeId;
    private Long productId;
    private Long variantA;
    private Long variantB;

    @BeforeEach
    void seed() {
        StoreEntity store = new StoreEntity();
        store.setSupplierIdRef(100L);
        store.setStoreCode("STR-CONTRACT0001");
        store.setName("Như Lan");
        store.setSlug("nhu-lan-contract");
        store.setStatus(StoreStatus.ACTIVE);
        storeId = storeRepository.save(store).getId();

        ProductEntity product = new ProductEntity();
        product.setStoreId(storeId);
        product.setCategoryId(1L);
        product.setProductCode("PRD-CONTRACT");
        product.setName("Bánh nướng thập cẩm");
        product.setStatus(ProductStatus.ACTIVE);
        productId = productRepository.save(product).getId();

        variantA = variant("SKU-CT-A", "Hộp 2 bánh", "180000", ProductStatus.ACTIVE);
        variantB = variant("SKU-CT-B", "Hộp 4 bánh", "320000", ProductStatus.DRAFT);
    }

    private Long variant(String sku, String name, String price, ProductStatus status) {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setProductId(productId);
        v.setSku(sku);
        v.setVariantName(name);
        v.setImageUrl("https://img/" + sku + ".png");
        v.setBasePrice(new BigDecimal(price));
        v.setStockQuantity(12);
        v.setStatus(status);
        return variantRepository.save(v).getId();
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder builder, String name, String token) {
        return builder.header("X-Service-Name", name).header("X-Service-Token", token);
    }

    private static MockHttpServletRequestBuilder batch(String body) {
        return post("/internal/v1/variants/snapshots").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ── IN06 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IN06: RFQ lấy snapshot đúng field contract v1")
    void in06_snapshot() throws Exception {
        mockMvc.perform(asService(get("/internal/v1/products/{p}/variants/{v}/snapshot", productId, variantA),
                        "rfq", RFQ_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.variantId").value(variantA))
                .andExpect(jsonPath("$.productName").value("Bánh nướng thập cẩm"))
                .andExpect(jsonPath("$.variantName").value("Hộp 2 bánh"))
                .andExpect(jsonPath("$.sku").value("SKU-CT-A"))
                .andExpect(jsonPath("$.agreedUnitPrice").value(180000));
    }

    @Test
    @DisplayName("IN06: variant không thuộc product → 404")
    void in06_wrongProduct_404() throws Exception {
        mockMvc.perform(asService(get("/internal/v1/products/{p}/variants/{v}/snapshot", productId + 999, variantA),
                        "commerce", COMMERCE_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ── IN07 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IN07: giữ thứ tự đầu vào, gộp ID trùng, trả đủ field kèm status")
    void in07_batch() throws Exception {
        String body = "{\"variantIds\":[" + variantB + "," + variantA + "," + variantB + "]}";

        mockMvc.perform(asService(batch(body), "commerce", COMMERCE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].variantId").value(variantB))
                .andExpect(jsonPath("$[0].variantStatus").value("DRAFT"))
                .andExpect(jsonPath("$[1].variantId").value(variantA))
                .andExpect(jsonPath("$[1].productId").value(productId))
                .andExpect(jsonPath("$[1].storeId").value(storeId))
                .andExpect(jsonPath("$[1].supplierId").value(100))
                .andExpect(jsonPath("$[1].productCode").value("PRD-CONTRACT"))
                .andExpect(jsonPath("$[1].productName").value("Bánh nướng thập cẩm"))
                .andExpect(jsonPath("$[1].variantName").value("Hộp 2 bánh"))
                .andExpect(jsonPath("$[1].sku").value("SKU-CT-A"))
                .andExpect(jsonPath("$[1].imageUrl").value("https://img/SKU-CT-A.png"))
                .andExpect(jsonPath("$[1].basePrice").value(180000))
                .andExpect(jsonPath("$[1].stockQuantity").value(12))
                .andExpect(jsonPath("$[1].variantStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[1].productStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[1].storeStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("IN07: có ID không tồn tại → 404, không âm thầm bỏ qua")
    void in07_missing_404() throws Exception {
        String body = "{\"variantIds\":[" + variantA + ",999999]}";

        mockMvc.perform(asService(batch(body), "commerce", COMMERCE_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("999999")));
    }

    @Test
    @DisplayName("IN07: danh sách rỗng → 400")
    void in07_empty_400() throws Exception {
        mockMvc.perform(asService(batch("{\"variantIds\":[]}"), "commerce", COMMERCE_TOKEN))
                .andExpect(status().isBadRequest());
    }

    // ── IN08 ──────────────────────────────────────────────────────────────────────

    @Autowired PromotionRepository promotionRepository;
    @Autowired PromotionRuleRepository ruleRepository;

    private Long activePercentPromotion(String pct) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PromotionEntity promotion = new PromotionEntity();
        promotion.setStoreId(storeId);
        promotion.setName("Trung thu 10%");
        promotion.setStartAt(now.minusDays(1));
        promotion.setEndAt(now.plusDays(30));
        promotion.setStatus(PromotionStatus.ACTIVE);
        Long promotionId = promotionRepository.save(promotion).getId();

        PromotionRuleEntity rule = new PromotionRuleEntity();
        rule.setPromotionId(promotionId);
        rule.setRuleType(PromotionType.PERCENT_DISCOUNT);
        rule.setTargetVariantId(variantA);
        rule.setDiscountPercent(new BigDecimal(pct));
        ruleRepository.save(rule);
        return promotionId;
    }

    private static MockHttpServletRequestBuilder calculate(String body) {
        return post("/internal/v1/promotions/calculate").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("IN08: Commerce tính khuyến mãi, giá lấy từ Catalog, đúng field contract")
    void in08_calculate() throws Exception {
        Long promotionId = activePercentPromotion("10");
        String body = "{\"storeId\":" + storeId + ",\"lines\":[{\"variantId\":" + variantA + ",\"quantity\":2}]}";

        mockMvc.perform(asService(calculate(body), "commerce", COMMERCE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId))
                .andExpect(jsonPath("$.calculatedAt").exists())
                .andExpect(jsonPath("$.subtotal").value(360000))
                .andExpect(jsonPath("$.discountTotal").value(36000))
                .andExpect(jsonPath("$.totalAmount").value(324000))
                .andExpect(jsonPath("$.lines[0].variantId").value(variantA))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(180000))
                .andExpect(jsonPath("$.lines[0].lineSubtotal").value(360000))
                .andExpect(jsonPath("$.lines[0].discountAmount").value(36000))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(324000))
                .andExpect(jsonPath("$.lines[0].appliedRules[0].promotionId").value(promotionId))
                .andExpect(jsonPath("$.appliedPromotions[0].promotionId").value(promotionId))
                .andExpect(jsonPath("$.appliedPromotions[0].promotionName").value("Trung thu 10%"))
                .andExpect(jsonPath("$.appliedPromotions[0].ruleType").value("PERCENT_DISCOUNT"))
                .andExpect(jsonPath("$.appliedPromotions[0].discountAmount").value(36000));
    }

    @Test
    @DisplayName("IN08: variant không thuộc storeId → 400; quantity <= 0 → 400")
    void in08_invalid_400() throws Exception {
        String wrongStore = "{\"storeId\":" + (storeId + 999) + ",\"lines\":[{\"variantId\":" + variantA + ",\"quantity\":1}]}";
        mockMvc.perform(asService(calculate(wrongStore), "commerce", COMMERCE_TOKEN))
                .andExpect(status().isBadRequest());

        String zeroQty = "{\"storeId\":" + storeId + ",\"lines\":[{\"variantId\":" + variantA + ",\"quantity\":0}]}";
        mockMvc.perform(asService(calculate(zeroQty), "commerce", COMMERCE_TOKEN))
                .andExpect(status().isBadRequest());
    }

    // ── Xác thực service-to-service ──────────────────────────────────────────────

    @Test
    @DisplayName("không có header service hoặc token sai → 401")
    void unauthenticatedCaller_401() throws Exception {
        String body = "{\"variantIds\":[" + variantA + "]}";

        mockMvc.perform(batch(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(asService(batch(body), "commerce", RFQ_TOKEN))
                .andExpect(status().isUnauthorized());
    }
}
