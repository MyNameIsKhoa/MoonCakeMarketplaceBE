package mss.mooncake.rfqnegotiation.util;

import java.util.Set;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Paging {
    private Paging() {
    }

    public static Pageable page(int page, int size, String sort, Set<String> allowed, String defaultField) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGINATION", "page must be >= 0 and size must be between 1 and 100");
        }
        String[] parts = (sort == null || sort.isBlank() ? defaultField + ",desc" : sort).split(",", -1);
        if (parts.length > 2 || !allowed.contains(parts[0])) {
            throw ApiException.badRequest("INVALID_SORT", "Unsupported sort field");
        }
        Sort.Direction direction;
        try {
            direction = parts.length == 1 ? Sort.Direction.ASC : Sort.Direction.fromString(parts[1]);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_SORT", "Sort direction must be asc or desc");
        }
        Sort ordering = Sort.by(direction, parts[0]);
        if (!"id".equals(parts[0])) {
            ordering = ordering.and(Sort.by(direction, "id"));
        }
        return PageRequest.of(page, size, ordering);
    }
}
