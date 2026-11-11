package mss.mooncake.identity.controller;

import java.time.LocalDateTime;
import mss.mooncake.identity.entity.UserEntity;
import mss.mooncake.identity.enums.UserStatus;
import mss.mooncake.identity.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/identity")
public class AdminUserController {
    public record UserRow(Long userId, String email, UserStatus status, boolean emailVerified,
                          LocalDateTime createdAt) {
        static UserRow from(UserEntity user) {
            return new UserRow(user.getId(), user.getEmail(), user.getStatus(), user.isEmailVerified(),
                    user.getCreatedAt());
        }
    }

    private final UserRepository users;

    public AdminUserController(UserRepository users) { this.users = users; }

    @GetMapping("/users")
    public Page<UserRow> users(@RequestParam(required = false) String q,
                               @RequestParam(required = false) UserStatus status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Tham số phân trang không hợp lệ");
        var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        String email = q == null ? "" : q.trim();
        Page<UserEntity> result = email.isEmpty()
                ? status == null ? users.findAll(paging) : users.findByStatus(status, paging)
                : status == null ? users.findByEmailContainingIgnoreCase(email, paging)
                        : users.findByEmailContainingIgnoreCaseAndStatus(email, status, paging);
        return result.map(UserRow::from);
    }
}
