package mss.mooncake.commerce.security;

public record CommercePrincipal(Long userId, Long customerId, String email) { }
