package mss.mooncake.catalogstore.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import mss.mooncake.catalogstore.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@DisplayName("PageQuery — quy ước phân trang MSS")
class PageQueryTest {

    private static final Set<String> FIELDS = Set.of("name", "id");

    private static List<String> orderProperties(Pageable pageable) {
        return pageable.getSort().stream().map(Sort.Order::getProperty).toList();
    }

    @Test
    @DisplayName("mặc định page 0, size 20, sort mặc định + id làm tie-breaker")
    void defaults() {
        Pageable p = new PageQuery(null, null, null).toPageable(FIELDS, Sort.by("name"));

        assertThat(p.getPageNumber()).isZero();
        assertThat(p.getPageSize()).isEqualTo(20);
        assertThat(orderProperties(p)).containsExactly("name", "id");
    }

    @Test
    @DisplayName("sort đã có id (mặc định hoặc client gửi) → không lặp id (SQL Server từ chối ORDER BY trùng cột)")
    void noDuplicateIdOrder() {
        assertThat(orderProperties(new PageQuery(null, null, null).toPageable(FIELDS, Sort.by("id"))))
                .containsExactly("id");
        Pageable desc = new PageQuery(null, null, "id,desc").toPageable(FIELDS, Sort.by("name"));
        assertThat(orderProperties(desc)).containsExactly("id");
        assertThat(desc.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("size ngoài 1..100, page âm, sort ngoài allowlist hoặc sai chiều → 400")
    void invalid() {
        assertThatThrownBy(() -> new PageQuery(null, 101, null).toPageable(FIELDS, Sort.by("id")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new PageQuery(null, 0, null).toPageable(FIELDS, Sort.by("id")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new PageQuery(-1, null, null).toPageable(FIELDS, Sort.by("id")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new PageQuery(null, null, "password").toPageable(FIELDS, Sort.by("id")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new PageQuery(null, null, "name,up").toPageable(FIELDS, Sort.by("id")))
                .isInstanceOf(BadRequestException.class);
    }
}
