package mss.mooncake.rfqnegotiation.service.impl;

import feign.FeignException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.identity.UserSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.request.CreateConversationRequest;
import mss.mooncake.rfqnegotiation.dto.request.SendMessageRequest;
import mss.mooncake.rfqnegotiation.dto.response.ConversationCreatedResponse;
import mss.mooncake.rfqnegotiation.dto.response.ConversationResponse;
import mss.mooncake.rfqnegotiation.dto.response.MessagePageResponse;
import mss.mooncake.rfqnegotiation.dto.response.MessageResponse;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.UnreadCountResponse;
import mss.mooncake.rfqnegotiation.entity.ConversationEntity;
import mss.mooncake.rfqnegotiation.entity.ConversationReadStateEntity;
import mss.mooncake.rfqnegotiation.entity.MessageEntity;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.enums.ConversationStatus;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;
import mss.mooncake.rfqnegotiation.enums.RfqAudience;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.notification.RfqNotifier;
import mss.mooncake.rfqnegotiation.repository.ConversationReadStateRepository;
import mss.mooncake.rfqnegotiation.repository.ConversationRepository;
import mss.mooncake.rfqnegotiation.repository.MessageRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationRepository;
import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import mss.mooncake.rfqnegotiation.repository.RfqTargetStoreRepository;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.service.ConversationApplicationService;
import mss.mooncake.rfqnegotiation.util.Paging;
import mss.mooncake.rfqnegotiation.websocket.MessagePersistedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ConversationApplicationServiceImpl implements ConversationApplicationService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationReadStateRepository reads;
    private final RfqRepository rfqs;
    private final RfqTargetStoreRepository targets;
    private final QuotationRepository quotations;
    private final ActorContext actor;
    private final IdentityServiceClient identity;
    private final ApplicationEventPublisher events;
    private final RfqNotifier notifier;

    public ConversationApplicationServiceImpl(ConversationRepository conversations, MessageRepository messages,
            ConversationReadStateRepository reads, RfqRepository rfqs, RfqTargetStoreRepository targets,
            QuotationRepository quotations, ActorContext actor, IdentityServiceClient identity, ApplicationEventPublisher events,
            RfqNotifier notifier) {
        this.conversations = conversations;
        this.messages = messages;
        this.reads = reads;
        this.rfqs = rfqs;
        this.targets = targets;
        this.quotations = quotations;
        this.actor = actor;
        this.identity = identity;
        this.events = events;
        this.notifier = notifier;
    }

    @Override
    @Transactional
    public ConversationCreatedResponse create(Long rfqId, CreateConversationRequest request) {
        actor.userId();
        // Lock the parent, including when no conversation row exists yet, to serialize creation.
        RfqEntity rfq = rfqs.findByIdForUpdate(rfqId)
                .orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy RFQ"));
        Long customerId = actor.optionalCustomerId();
        boolean owner = Objects.equals(customerId, rfq.getCustomerIdRef());
        Long supplierId;
        if (owner) {
            if (request == null || request.supplierId() == null || request.supplierId() <= 0) {
                throw ApiException.badRequest("SUPPLIER_ID_REQUIRED", "Chủ RFQ cần chọn nhà cung cấp để trao đổi");
            }
            supplierId = request.supplierId();
        } else {
            supplierId = actor.supplierId();
            if (request != null && request.supplierId() != null && !supplierId.equals(request.supplierId())) {
                throw ApiException.forbidden("NOT_SUPPLIER_OWNER", "Chỉ được mở hội thoại bằng hồ sơ nhà cung cấp của mình");
            }
        }
        if (rfq.getAudience() == RfqAudience.SELECTED_STORES && !targets.existsByRfqIdAndSupplierIdRef(rfqId, supplierId)) {
            // The buyer learns the supplier was not invited; an uninvited supplier does not learn the RFQ exists.
            throw owner ? ApiException.forbidden("SUPPLIER_NOT_TARGETED", "Nhà cung cấp này không được mời cho yêu cầu báo giá")
                    : ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy RFQ");
        }
        SupplierSummaryResponse supplier = requireSupplier(supplierId);
        Optional<ConversationEntity> existing = conversations.findByRfqIdAndSupplierIdRef(rfqId, supplierId);
        if (existing.isPresent()) {
            return new ConversationCreatedResponse(toResponse(existing.get(), rfq.getCustomerNameSnapshot()), false);
        }
        if (!"VERIFIED".equals(supplier.verificationStatus())) {
            throw ApiException.conflict("SUPPLIER_NOT_VERIFIED", "Nhà cung cấp chưa được xác minh nên chưa thể mở hội thoại");
        }
        // After intake ends, a supplier whose quotation was accepted still needs a channel for delivery details.
        boolean accepted = quotations.existsByRfqIdAndSupplierIdRefAndStatus(rfqId, supplierId, QuotationStatus.ACCEPTED);
        if (!accepted && (rfq.getStatus() != RfqStatus.OPEN || (rfq.getExpiresAt() != null
                && !rfq.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))))) {
            throw ApiException.conflict("RFQ_NOT_OPEN", "Chỉ mở hội thoại mới khi RFQ đang nhận trao đổi");
        }
        ConversationEntity conversation = new ConversationEntity();
        conversation.setRfqId(rfqId);
        conversation.setCustomerIdRef(rfq.getCustomerIdRef());
        conversation.setSupplierIdRef(supplierId);
        conversation.setStatus(ConversationStatus.OPEN);
        conversation.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).withNano(0));
        return new ConversationCreatedResponse(
                toResponse(conversations.saveAndFlush(conversation), rfq.getCustomerNameSnapshot()), true);
    }

    @Override
    public PageResponse<ConversationResponse> list(Long rfqId, int page, int size, String sort) {
        actor.userId();
        Pageable pageable = Paging.page(page, size, sort, Set.of("id", "createdAt", "status"), "createdAt");
        Page<ConversationEntity> found = conversations.findForParticipant(actor.optionalCustomerId(), actor.optionalSupplierId(), rfqId, pageable);
        // One query for the buyer names of the whole page.
        Map<Long, String> buyerNames = new HashMap<>();
        rfqs.findAllById(found.map(ConversationEntity::getRfqId).toSet())
                .forEach(rfq -> buyerNames.put(rfq.getId(), rfq.getCustomerNameSnapshot()));
        return PageResponse.from(found.map(c -> toResponse(c, buyerNames.get(c.getRfqId()))));
    }

    @Override
    public ConversationResponse get(Long conversationId) {
        ConversationEntity conversation = requireConversation(conversationId);
        requireParticipant(conversation, actor.userId());
        String buyerName = rfqs.findById(conversation.getRfqId()).map(RfqEntity::getCustomerNameSnapshot).orElse(null);
        return toResponse(conversation, buyerName);
    }

    @Override
    public void assertParticipant(Long conversationId, Long userId) {
        requireParticipant(requireConversation(conversationId), userId);
    }

    @Override
    public MessagePageResponse history(Long conversationId, Long beforeMessageId, Long afterMessageId, int limit) {
        requireParticipant(requireConversation(conversationId), actor.userId());
        if (limit < 1 || limit > 100 || (beforeMessageId != null && afterMessageId != null)) {
            throw ApiException.badRequest("INVALID_MESSAGE_PAGINATION", "limit phải từ 1 đến 100; chỉ chọn một chiều cursor");
        }
        Long cursor = beforeMessageId != null ? beforeMessageId : afterMessageId;
        if (cursor != null && (cursor <= 0 || !messages.existsByIdAndConversationId(cursor, conversationId))) {
            throw ApiException.badRequest("INVALID_MESSAGE_CURSOR", "Cursor không thuộc hội thoại này");
        }
        List<MessageEntity> batch = afterMessageId != null
                ? messages.findAfter(conversationId, afterMessageId, PageRequest.of(0, limit + 1))
                : messages.findBefore(conversationId, beforeMessageId, PageRequest.of(0, limit + 1));
        boolean hasMore = batch.size() > limit;
        ArrayList<MessageEntity> selected = new ArrayList<>(batch.subList(0, Math.min(limit, batch.size())));
        if (afterMessageId == null) Collections.reverse(selected);
        List<MessageResponse> items = selected.stream().map(this::toResponse).toList();
        String nextCursor = !hasMore || items.isEmpty() ? null : (afterMessageId == null
                ? items.getFirst().messageId() : items.getLast().messageId());
        return new MessagePageResponse(items, nextCursor, hasMore);
    }

    @Override
    @Transactional
    public MessageResponse send(Long conversationId, Long userId, SendMessageRequest request) {
        validateMessage(request);
        // One conversation lock protects dedup across concurrent sessions; the DB unique is the final guard.
        ConversationEntity conversation = conversations.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "Không tìm thấy hội thoại"));
        Participant sender = requireParticipant(conversation, userId);
        Optional<MessageEntity> existing = messages.findByConversationIdAndSenderUserIdRefAndClientMessageId(
                conversationId, userId, request.clientMessageId());
        if (existing.isPresent()) {
            MessageEntity stored = existing.get();
            if (!stored.getMessageType().equals(request.messageType()) || !stored.getContent().equals(request.content())) {
                throw ApiException.conflict("CLIENT_MESSAGE_ID_REUSED", "clientMessageId đã dùng cho một nội dung khác");
            }
            MessageResponse response = toResponse(stored);
            // Echo the durable message again when its earlier delivery/ack was lost; clients dedup messageId.
            events.publishEvent(new MessagePersistedEvent(response));
            return response;
        }
        if (conversation.getStatus() != ConversationStatus.OPEN) {
            throw ApiException.conflict("CONVERSATION_NOT_OPEN", "Hội thoại đã đóng hoặc bị khóa");
        }
        MessageEntity message = new MessageEntity();
        message.setConversationId(conversationId);
        message.setSenderUserIdRef(userId);
        message.setClientMessageId(request.clientMessageId());
        message.setMessageType(request.messageType());
        message.setContent(request.content());
        message.setSentAt(LocalDateTime.now(ZoneOffset.UTC).withNano(0));
        MessageResponse response = toResponse(messages.saveAndFlush(message));
        events.publishEvent(new MessagePersistedEvent(response));
        Long recipientLastRead = reads.findFirstByConversationIdAndUserIdRefNotOrderByLastReadMessageIdDesc(conversationId, userId)
                .map(ConversationReadStateEntity::getLastReadMessageId).orElse(null);
        notifier.chatMessage(conversation, sender.buyer(), sender.displayName(), request.content(), recipientLastRead);
        return response;
    }

    @Override
    public UnreadCountResponse unreadCount() {
        Long userId = actor.userId();
        return new UnreadCountResponse(messages.countUnread(userId, actor.optionalCustomerId(), actor.optionalSupplierId()));
    }

    @Override
    @Transactional
    public void markRead(Long conversationId) {
        Long userId = actor.userId();
        // The conversation lock serializes several tabs of the same user creating the unique read row.
        ConversationEntity conversation = conversations.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "Không tìm thấy hội thoại"));
        requireParticipant(conversation, userId);
        Long latest = messages.findLatestId(conversationId);
        if (latest == null) return;
        ConversationReadStateEntity read = reads.findByConversationIdAndUserIdRef(conversationId, userId)
                .orElseGet(() -> new ConversationReadStateEntity(conversationId, userId));
        read.readUpTo(latest, LocalDateTime.now(ZoneOffset.UTC).withNano(0));
        reads.save(read);
    }

    private void validateMessage(SendMessageRequest request) {
        if (request == null || request.clientMessageId() == null || request.clientMessageId().length() > 100
                || !request.clientMessageId().matches("[A-Za-z0-9_-]+") || !"TEXT".equals(request.messageType())
                || request.content() == null || request.content().isBlank() || request.content().length() > 10000) {
            throw ApiException.badRequest("INVALID_MESSAGE", "Tin nhắn TEXT cần clientMessageId hợp lệ và nội dung từ 1 đến 10000 ký tự");
        }
    }

    private ConversationEntity requireConversation(Long id) {
        return conversations.findById(id)
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "Không tìm thấy hội thoại"));
    }

    /** Which side the user is on, and the name the other side knows them by. */
    private record Participant(boolean buyer, String displayName) { }

    private Participant requireParticipant(ConversationEntity conversation, Long userId) {
        if (userId == null) throw ApiException.forbidden("NOT_CONVERSATION_PARTICIPANT", "Bạn không thuộc hội thoại này");
        try {
            UserSummaryResponse user = identity.getUserSummary(userId);
            if (user == null || !Objects.equals(user.userId(), userId)) {
                throw ApiException.unavailable("IDENTITY_UNAVAILABLE", "Không xác minh được tài khoản");
            }
            if (Objects.equals(user.customerId(), conversation.getCustomerIdRef())) return new Participant(true, user.fullName());
            SupplierSummaryResponse supplier = requireSupplier(conversation.getSupplierIdRef());
            if (Objects.equals(supplier.userId(), userId)) return new Participant(false, supplier.companyName());
        } catch (FeignException ex) {
            throw ApiException.unavailable("IDENTITY_UNAVAILABLE", "Không xác minh được quyền hội thoại");
        }
        throw ApiException.forbidden("NOT_CONVERSATION_PARTICIPANT", "Bạn không thuộc hội thoại này");
    }

    private SupplierSummaryResponse requireSupplier(Long supplierId) {
        try {
            SupplierSummaryResponse supplier = identity.getSupplierSummary(supplierId);
            if (supplier == null || supplier.userId() == null || !Objects.equals(supplier.supplierId(), supplierId)) {
                throw ApiException.unavailable("IDENTITY_UNAVAILABLE", "Không xác minh được nhà cung cấp");
            }
            return supplier;
        } catch (FeignException.NotFound ex) {
            throw ApiException.notFound("SUPPLIER_NOT_FOUND", "Không tìm thấy nhà cung cấp");
        } catch (FeignException ex) {
            throw ApiException.unavailable("IDENTITY_UNAVAILABLE", "Không xác minh được nhà cung cấp");
        }
    }

    private ConversationResponse toResponse(ConversationEntity entity, String buyerName) {
        return new ConversationResponse(entity.getId().toString(), entity.getRfqId().toString(),
                entity.getCustomerIdRef().toString(), entity.getSupplierIdRef().toString(),
                entity.getStatus(), entity.getCreatedAt().toInstant(ZoneOffset.UTC), buyerName);
    }

    private MessageResponse toResponse(MessageEntity entity) {
        return new MessageResponse(entity.getId().toString(), entity.getConversationId().toString(),
                entity.getSenderUserIdRef().toString(), entity.getMessageType(), entity.getContent(),
                entity.getSentAt().toInstant(ZoneOffset.UTC), entity.getClientMessageId());
    }
}
