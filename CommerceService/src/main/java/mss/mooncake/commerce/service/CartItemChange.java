package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.response.CartItemResponse;

public record CartItemChange(CartItemResponse item, boolean created) { }
