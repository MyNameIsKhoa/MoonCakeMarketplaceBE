package mss.mooncake.commerce.client.dto.catalog;

import java.util.List;

/** Catalog per-line restore: {@code restoreKey} makes retries return the same lines to stock only once. */
public record StockRestoreRequest(String restoreKey, List<StockReservationRequest.Line> lines) { }
