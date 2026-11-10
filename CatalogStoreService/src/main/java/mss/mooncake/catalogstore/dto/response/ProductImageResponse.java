package mss.mooncake.catalogstore.dto.response;

public record ProductImageResponse(String url, String contentType, int width, int height, long size) {}
