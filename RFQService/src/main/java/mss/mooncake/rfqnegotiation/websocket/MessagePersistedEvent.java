package mss.mooncake.rfqnegotiation.websocket;

import mss.mooncake.rfqnegotiation.dto.response.MessageResponse;

public record MessagePersistedEvent(MessageResponse message) { }
