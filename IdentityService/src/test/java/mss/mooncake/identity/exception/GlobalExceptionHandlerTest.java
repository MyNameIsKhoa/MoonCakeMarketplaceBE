package mss.mooncake.identity.exception;

import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Các lỗi khó tái hiện qua endpoint thật (vi phạm ràng buộc DB, thiếu header, lỗi bất ngờ). */
class GlobalExceptionHandlerTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/unique")
        void unique() {
            throw new DataIntegrityViolationException("could not execute statement [insert into app_user]",
                    new SQLException("Violation of UNIQUE KEY constraint 'UQ_app_user_email'. Cannot insert duplicate key",
                            "23000", 2627));
        }

        @GetMapping("/probe/foreign-key")
        void foreignKey() {
            throw new DataIntegrityViolationException("could not execute statement [insert into address]",
                    new SQLException("The INSERT statement conflicted with the FOREIGN KEY constraint", "23000", 547));
        }

        @GetMapping("/probe/header")
        String header(@RequestHeader("X-Request-Id") String requestId) {
            return requestId;
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
    void unsupportedContentTypeKeeps415WithVietnameseMessage() throws Exception {
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
    void uniqueConstraintViolationReturns409WithoutSqlText() throws Exception {
        mockMvc.perform(get("/probe/unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", not(containsString("UQ_"))))
                .andExpect(jsonPath("$.message", not(containsString("insert"))));
    }

    @Test
    void otherConstraintViolationReturns400WithoutSqlText() throws Exception {
        mockMvc.perform(get("/probe/foreign-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", not(containsString("FOREIGN KEY"))))
                .andExpect(jsonPath("$.message", not(containsString("insert"))));
    }

    @Test
    void missingRequiredHeaderReturns400NamingTheHeader() throws Exception {
        mockMvc.perform(get("/probe/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("X-Request-Id")));
    }

    @Test
    void unexpectedErrorReturns500WithoutExceptionText() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message", not(containsString("secret"))))
                .andExpect(jsonPath("$.message", not(containsString("hibernate"))));
    }
}
