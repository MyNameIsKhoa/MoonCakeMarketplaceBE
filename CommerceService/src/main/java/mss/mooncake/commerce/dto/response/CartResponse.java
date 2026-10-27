package mss.mooncake.commerce.dto.response;

import java.util.List;

public record CartResponse(Long cartId, List<CartItemResponse> items) { }
