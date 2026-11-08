package mss.mooncake.catalogstore.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateStoreRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreRequest;
import mss.mooncake.catalogstore.dto.response.StoreResponse;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.StoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StoreController.class)
@Import({mss.mooncake.catalogstore.security.SecurityConfig.class, mss.mooncake.catalogstore.exception.GlobalExceptionHandler.class})
@DisplayName("StoreController MockMvc Tests")
class StoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private StoreService storeService;

    // Filter beans needed by SecurityConfig
    @MockitoBean
    private mss.mooncake.catalogstore.security.JwtAuthenticationFilter jwtAuthenticationFilter;

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.ServletRequest req = inv.getArgument(0);
            jakarta.servlet.ServletResponse res = inv.getArgument(1);
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static final Long STORE_ID = 1L;
    private static final Long SUPPLIER_ID = 100L;
    private static final Long USER_ID = 10L;

    private UserPrincipal principalA() {
        return UserPrincipal.create(USER_ID, "supplierA@test.com", List.of("ROLE_SUPPLIER"));
    }

    private StoreResponse sampleResponse() {
        return new StoreResponse(STORE_ID, SUPPLIER_ID, "STR-ABCD1234", "Store A",
                "store-a", null, null, null, "DRAFT", null);
    }

    // ── GET /api/stores — public ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/stores trả 200 và danh sách store — public, không cần JWT")
    void getAllStores_public_200() throws Exception {
        when(storeService.getAllStores()).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].storeId").value(STORE_ID))
                .andExpect(jsonPath("$[0].name").value("Store A"));
    }

    @Test
    @DisplayName("GET /api/stores/{id} trả 200 — public")
    void getStoreById_200() throws Exception {
        when(storeService.getStoreByIdOrSlug(String.valueOf(STORE_ID), null)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/stores/{id}", STORE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeCode").value("STR-ABCD1234"));
    }

    @Test
    @DisplayName("GET /api/stores/{id} trả 404 khi store không tồn tại")
    void getStoreById_404() throws Exception {
        when(storeService.getStoreByIdOrSlug("999", null)).thenThrow(new ResourceNotFoundException("Không tìm thấy"));

        mockMvc.perform(get("/api/stores/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanRequestDraftStoreDetail() throws Exception {
        when(storeService.getStoreByIdOrSlug("1", USER_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/stores/1").with(SecurityMockMvcRequestPostProcessors.user(principalA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    // ── POST /api/stores ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/stores trả 201 khi user là supplier hợp lệ")
    void createStore_201() throws Exception {
        when(storeService.createStore(any(CreateStoreRequest.class), eq(USER_ID)))
                .thenReturn(sampleResponse());

        CreateStoreRequest req = new CreateStoreRequest("Store A", null, null, null, null);

        mockMvc.perform(post("/api/stores")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(STORE_ID));
    }

    @Test
    @DisplayName("POST /api/stores trả 401 khi không có JWT")
    void createStore_401_noJwt() throws Exception {
        CreateStoreRequest req = new CreateStoreRequest("Store A", null, null, null, null);

        mockMvc.perform(post("/api/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/stores trả 403 khi JWT chỉ có ROLE_CUSTOMER (chưa là supplier VERIFIED)")
    void createStore_403_customerRoleOnly() throws Exception {
        CreateStoreRequest req = new CreateStoreRequest("Store A", null, null, null, null);
        UserPrincipal customer = UserPrincipal.create(USER_ID, "buyer@test.com", List.of("ROLE_CUSTOMER"));

        mockMvc.perform(post("/api/stores")
                        .with(SecurityMockMvcRequestPostProcessors.user(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("POST /api/stores trả 400 khi thiếu name")
    void createStore_400_missingName() throws Exception {
        // name = blank
        CreateStoreRequest req = new CreateStoreRequest("", null, null, null, null);

        mockMvc.perform(post("/api/stores")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/stores trả 400 khi logoUrl không phải http(s)")
    void createStore_400_nonHttpLogoUrl() throws Exception {
        CreateStoreRequest req = new CreateStoreRequest("Store A", "javascript:alert(1)", null, null, null);

        mockMvc.perform(post("/api/stores")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.logoUrl").exists());
        org.mockito.Mockito.verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("PATCH /api/stores/{id} trả 400 khi bannerUrl không phải http(s); https và chuỗi rỗng (xóa ảnh) hợp lệ")
    void updateStore_bannerUrlMustBeHttp() throws Exception {
        mockMvc.perform(patch("/api/stores/{id}", STORE_ID)
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bannerUrl\":\"ftp://cdn.example.com/banner.png\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bannerUrl").exists());

        when(storeService.updateStore(eq(STORE_ID), any(UpdateStoreRequest.class), eq(USER_ID), eq(false)))
                .thenReturn(sampleResponse());
        mockMvc.perform(patch("/api/stores/{id}", STORE_ID)
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bannerUrl\":\"https://cdn.example.com/banner.png\",\"logoUrl\":\"\"}"))
                .andExpect(status().isOk());
    }

    // ── PATCH /api/stores/{id} ────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/stores/{id} trả 200 khi user là owner")
    void updateStore_200_owner() throws Exception {
        when(storeService.updateStore(eq(STORE_ID), any(UpdateStoreRequest.class), eq(USER_ID), eq(false)))
                .thenReturn(sampleResponse());

        UpdateStoreRequest req = new UpdateStoreRequest("New Name", null, null, "dark", null, null);

        mockMvc.perform(patch("/api/stores/{id}", STORE_ID)
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/stores/{id} trả 403 khi user không phải owner")
    void updateStore_403_notOwner() throws Exception {
        when(storeService.updateStore(eq(STORE_ID), any(UpdateStoreRequest.class), eq(USER_ID), eq(false)))
                .thenThrow(new ForbiddenException("Bạn không có quyền"));

        UpdateStoreRequest req = new UpdateStoreRequest("Hacked", null, null, null, null, null);

        mockMvc.perform(patch("/api/stores/{id}", STORE_ID)
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/stores/{id} trả 401 khi không có JWT")
    void updateStore_401_noJwt() throws Exception {
        UpdateStoreRequest req = new UpdateStoreRequest(null, null, null, null, null, null);

        mockMvc.perform(patch("/api/stores/{id}", STORE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
