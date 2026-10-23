package mss.mooncake.rfqnegotiation.dto.response;

/** Application result used to select 201 for a new conversation and 200 for an existing one. */
public record ConversationCreatedResponse(ConversationResponse conversation, boolean created) { }
