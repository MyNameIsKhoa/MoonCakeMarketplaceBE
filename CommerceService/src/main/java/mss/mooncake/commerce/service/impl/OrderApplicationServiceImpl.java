package mss.mooncake.commerce.service.impl;

import mss.mooncake.commerce.service.OrderApplicationService;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.dto.response.OrderResponse;
import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import mss.mooncake.commerce.repository.SellerOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderApplicationServiceImpl implements OrderApplicationService {
    private final SellerOrderRepository sellerOrders;

    public OrderApplicationServiceImpl(SellerOrderRepository sellerOrders) {
        this.sellerOrders = sellerOrders;
    }

    @Override
    public OrderResponse createOrderFromAcceptedQuotation(CreateOrderFromQuotationRequest request) {
        // shortcut: RFQ conversion has no durable dedup/recovery or payment obligation yet; remove with IN09/IN10.
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Quotation-to-order conversion requires durable deduplication and payment obligation");
    }

    @Override
    @Transactional(readOnly = true)
    public SellerOrderPaymentContext getSellerOrderPaymentContext(Long sellerOrderId) {
        SellerOrderEntity seller = sellerOrders.findById(sellerOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller order not found"));
        return new SellerOrderPaymentContext(seller.getId(), seller.getTotalAmount(),
                seller.getDepositPercent(), seller.getRemainingDueAt(), seller.getStatus());
    }
}
