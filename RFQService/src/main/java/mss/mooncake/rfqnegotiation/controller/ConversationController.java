package mss.mooncake.rfqnegotiation.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import mss.mooncake.rfqnegotiation.dto.request.CreateConversationRequest;
import mss.mooncake.rfqnegotiation.dto.response.ConversationCreatedResponse;
import mss.mooncake.rfqnegotiation.dto.response.ConversationResponse;
import mss.mooncake.rfqnegotiation.dto.response.MessagePageResponse;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.UnreadCountResponse;
import mss.mooncake.rfqnegotiation.service.ConversationApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConversationController {
    private final ConversationApplicationService conversations;
    public ConversationController(ConversationApplicationService conversations) { this.conversations = conversations; }

    @PostMapping("/api/rfqs/{rfqId}/conversations")
    @Operation(summary = "Tạo hoặc lấy hội thoại; supplier dùng hồ sơ của mình, buyer chọn supplierId")
    public ResponseEntity<ConversationResponse> create(@PathVariable Long rfqId,
            @Valid @RequestBody(required = false) CreateConversationRequest request) {
        ConversationCreatedResponse result = conversations.create(rfqId, request);
        return result.created() ? ResponseEntity.created(URI.create("/api/conversations/"
                + result.conversation().conversationId())).body(result.conversation()) : ResponseEntity.ok(result.conversation());
    }

    @GetMapping("/api/conversations")
    @Operation(summary = "Danh sách hội thoại của tài khoản", description = "Sort hỗ trợ id, createdAt, status; page từ 0, size tối đa 100")
    public PageResponse<ConversationResponse> list(@RequestParam(required = false) Long rfqId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return conversations.list(rfqId, page, size, sort);
    }

    @GetMapping("/api/conversations/{conversationId}")
    public ConversationResponse get(@PathVariable Long conversationId) { return conversations.get(conversationId); }

    @GetMapping("/api/conversations/unread-count")
    @Operation(summary = "Số tin nhắn chưa đọc của tài khoản", description = "Tin do bên kia gửi sau lần đọc cuối, trên mọi hội thoại")
    public UnreadCountResponse unreadCount() { return conversations.unreadCount(); }

    @PostMapping("/api/conversations/{conversationId}/read")
    @Operation(summary = "Đánh dấu đã đọc tới tin mới nhất của hội thoại")
    public ResponseEntity<Void> markRead(@PathVariable Long conversationId) {
        conversations.markRead(conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/conversations/{conversationId}/messages")
    @Operation(summary = "Lịch sử tin nhắn theo cursor", description = "Mặc định lấy 50 tin mới nhất; items tăng dần theo messageId. "
            + "beforeMessageId lấy tin cũ, afterMessageId lấy tin mới; cursor loại trừ và không được trộn hai chiều. limit 1..100.")
    public MessagePageResponse history(@PathVariable Long conversationId,
            @RequestParam(required = false) Long beforeMessageId, @RequestParam(required = false) Long afterMessageId,
            @RequestParam(defaultValue = "50") int limit) {
        return conversations.history(conversationId, beforeMessageId, afterMessageId, limit);
    }
}
