package mss.mooncake.commerce.service.impl;
import mss.mooncake.commerce.service.PaymentApplicationService;
import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;
import org.springframework.stereotype.Service;
@Service
public class PaymentApplicationServiceImpl implements PaymentApplicationService {
    @Override public SellerOrderPaymentContext getPaymentContext(Long sellerOrderId) {
        throw new UnsupportedOperationException("TODO: implement seller-order payment context");
    }
}
