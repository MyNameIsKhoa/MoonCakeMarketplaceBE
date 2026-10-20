package mss.mooncake.catalogstore.dto.response;

public record CategoryResponse(Long categoryId, Long parentCategoryId, String name, String slug, String status) { }
