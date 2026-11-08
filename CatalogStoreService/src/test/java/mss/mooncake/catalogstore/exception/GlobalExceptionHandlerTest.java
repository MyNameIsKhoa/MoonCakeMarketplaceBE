package mss.mooncake.catalogstore.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.List;
import mss.mooncake.catalogstore.entity.StoreEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Các lỗi khó tái hiện qua endpoint thật: vi phạm ràng buộc DB, sort sai trường, thiếu header, lỗi bất ngờ. */
@DisplayName("GlobalExceptionHandler — không lộ nội dung exception")
class GlobalExceptionHandlerTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/unique")
        void unique() {
            throw new DataIntegrityViolationException("could not execute statement [insert into store]",
                    new SQLException("Violation of UNIQUE KEY constraint 'UQ_store_slug'. Cannot insert duplicate key",
                            "23000", 2627));
        }

        @GetMapping("/probe/foreign-key")
        void foreignKey() {
            throw new DataIntegrityViolationException("could not execute statement [insert into product]",
                    new SQLException("The INSERT statement conflicted with the FOREIGN KEY constraint", "23000", 547));
        }

        @GetMapping("/probe/sort")
        void sort() {
            throw new InvalidDataAccessApiUsageException("No property 'foo' found",
                    new PropertyReferenceException("foo", TypeInformation.of(StoreEntity.class), List.of()));
        }

        @GetMapping("/probe/header")
        String header(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("org.hibernate secret internal detail");
        }

        @PostMapping(path = "/probe/json", consumes = "application/json")
        void json(@RequestBody java.util.Map<String, Object> body) {
        }
    }

    @Test
    @DisplayName("Content-Type không hỗ trợ → giữ 415, message tiếng Việt")
    void unsupportedContentType() throws Exception {
        mockMvc.perform(post("/probe/json").contentType("text/plain").content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message", containsString("application/json")));
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("vi phạm UNIQUE → 409, không lộ SQL")
    void uniqueViolation() throws Exception {
        mockMvc.perform(get("/probe/unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", not(containsString("UQ_"))))
                .andExpect(jsonPath("$.message", not(containsString("insert"))));
    }

    @Test
    @DisplayName("vi phạm ràng buộc khác → 400, không lộ SQL")
    void otherConstraintViolation() throws Exception {
        mockMvc.perform(get("/probe/foreign-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", not(containsString("FOREIGN KEY"))));
    }

    @Test
    @DisplayName("sort theo trường không tồn tại → 400")
    void invalidSortProperty() throws Exception {
        mockMvc.perform(get("/probe/sort"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("foo")))
                .andExpect(jsonPath("$.message", not(containsString("No property"))));
    }

    @Test
    @DisplayName("thiếu header bắt buộc → 400 nêu tên header")
    void missingHeader() throws Exception {
        mockMvc.perform(get("/probe/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Idempotency-Key")));
    }

    @Test
    @DisplayName("lỗi bất ngờ → 500 với message chung, không kèm exception")
    void unexpectedError() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message", not(containsString("secret"))))
                .andExpect(jsonPath("$.message", not(containsString("hibernate"))));
    }
}
