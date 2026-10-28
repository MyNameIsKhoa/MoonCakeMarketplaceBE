package mss.mooncake.rfqnegotiation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Messages sent by the other party after the caller's last read, across all of the caller's conversations.")
public record UnreadCountResponse(long unreadCount) { }
