package mss.mooncake.rfqnegotiation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "items always ascend by messageId. Initially fetch the latest messages; nextCursor "
        + "is the oldest returned ID for beforeMessageId, or newest ID for afterMessageId. Cursors are exclusive.")
public record MessagePageResponse(List<MessageResponse> items, String nextCursor, boolean hasMore) { }
