package mss.mooncake.catalogstore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.FilterChain;
import java.util.List;
import mss.mooncake.catalogstore.dto.response.ProductImageResponse;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.GlobalExceptionHandler;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.security.InternalServiceTokenFilter;
import mss.mooncake.catalogstore.security.JwtAuthenticationFilter;
import mss.mooncake.catalogstore.security.SecurityConfig;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.ProductImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductImageController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ProductImageControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProductImageService images;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean InternalServiceTokenFilter internal;
    final MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[] {1});
    final String filename = "12345678-1234-1234-1234-123456789abc.png";

    @BeforeEach
    void filters() throws Exception {
        doAnswer(inv -> { ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(jwt).doFilter(any(), any(), any());
        doAnswer(inv -> { ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(internal).doFilter(any(), any(), any());
    }

    UserPrincipal supplier() { return UserPrincipal.create(10L, "supplier@test.com", List.of("ROLE_SUPPLIER")); }

    @Test
    void uploadRequiresAuthenticationAndSupplierRole() throws Exception {
        mvc.perform(multipart("/api/products/5/images").file(file)).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/products/5/images").file(file).with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(images);
    }

    @Test
    void ownerUploadUsesPrincipalAndReturnsCreatedPreviewMetadata() throws Exception {
        String url = "http://localhost:8080/uploads/catalog/" + filename;
        when(images.upload(eq(5L), eq(10L), any())).thenReturn(new ProductImageResponse(url, "image/png", 40, 30, 100));
        mvc.perform(multipart("/api/products/5/images").file(file).with(user(supplier())))
                .andExpect(status().isCreated()).andExpect(header().string("Location", url))
                .andExpect(jsonPath("$.url").value(url)).andExpect(jsonPath("$.width").value(40))
                .andExpect(jsonPath("$.contentType").value("image/png"));
        verify(images).upload(eq(5L), eq(10L), any());
    }

    @Test
    void nonOwnerIsForbiddenAndMissingPartIsBadRequest() throws Exception {
        when(images.upload(eq(5L), eq(10L), any())).thenThrow(new ForbiddenException("Not owner"));
        mvc.perform(multipart("/api/products/5/images").file(file).with(user(supplier())))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/products/5/images").with(user(supplier())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousImageGetHasCorrectMimeCacheNosniffAndCors() throws Exception {
        when(images.load(filename)).thenReturn(new ByteArrayResource(new byte[] {1, 2, 3}));
        mvc.perform(get("/uploads/catalog/" + filename).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
        mvc.perform(post("/uploads/catalog/" + filename)).andExpect(status().isUnauthorized());
    }

    @Test
    void missingImageIs404AndCorsRejectsUnknownOrigins() throws Exception {
        when(images.load(filename)).thenThrow(new ResourceNotFoundException("Missing image"));
        mvc.perform(get("/uploads/catalog/" + filename)).andExpect(status().isNotFound());
        mvc.perform(get("/uploads/catalog/" + filename).header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void multipartOverflowReturns413WithUsefulMessage() throws Exception {
        when(images.upload(eq(5L), eq(10L), any()))
                .thenThrow(new org.springframework.web.multipart.MaxUploadSizeExceededException(5 * 1024 * 1024));
        mvc.perform(multipart("/api/products/5/images").file(file).with(user(supplier())))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.message").value("Ảnh tải lên vượt quá giới hạn 5 MiB."));
    }
}
