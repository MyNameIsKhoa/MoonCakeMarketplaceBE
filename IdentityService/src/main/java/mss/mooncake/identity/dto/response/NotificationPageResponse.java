package mss.mooncake.identity.dto.response;

import java.util.List;

/** Same page shape as the RFQ service: items plus page metadata. */
public record NotificationPageResponse(List<NotificationResponse> items, int page, int size,
                                       long totalElements, int totalPages) { }
