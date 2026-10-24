package mss.mooncake.commerce.dto.internal;

import mss.mooncake.commerce.dto.response.CartItemResponse;

public record CartItemChange(CartItemResponse item, boolean created) { }
