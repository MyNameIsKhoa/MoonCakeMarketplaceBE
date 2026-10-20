package mss.mooncake.catalogstore.dto.request;

import java.util.Set;
import mss.mooncake.catalogstore.exception.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

/**
 * Query phân trang {@code ?page=&size=&sort=} cho API listing; controller nhận trực tiếp như tham số (model attribute).
 * Quy ước MSS: page từ 0, size mặc định 20, tối đa 100; vượt giới hạn trả 400, không tự cắt.
 * Sort dạng "field" hoặc "field,asc|desc", field phải nằm trong allowlist của từng API; luôn thêm id làm tie-breaker.
 */
public record PageQuery(Integer page, Integer size, String sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public Pageable toPageable(Set<String> allowedSortFields, Sort defaultSort) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) throw new BadRequestException("page phải >= 0");
        if (s < 1 || s > MAX_SIZE) throw new BadRequestException("size phải trong khoảng 1.." + MAX_SIZE);
        return PageRequest.of(p, s, parseSort(allowedSortFields, defaultSort).and(Sort.by("id")));
    }

    private Sort parseSort(Set<String> allowedSortFields, Sort defaultSort) {
        if (!StringUtils.hasText(sort)) return defaultSort;
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (parts.length > 2 || !allowedSortFields.contains(field)) {
            throw new BadRequestException("sort không hợp lệ: " + sort + ". Cho phép: " + allowedSortFields);
        }
        if (parts.length == 1) return Sort.by(Sort.Direction.ASC, field);
        String direction = parts[1].trim();
        if ("asc".equalsIgnoreCase(direction)) return Sort.by(Sort.Direction.ASC, field);
        if ("desc".equalsIgnoreCase(direction)) return Sort.by(Sort.Direction.DESC, field);
        throw new BadRequestException("Chiều sort chỉ nhận asc hoặc desc: " + sort);
    }
}
