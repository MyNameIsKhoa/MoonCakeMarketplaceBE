package mss.mooncake.rfqnegotiation.config;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.conversion-recovery.enabled", havingValue="true", matchIfMissing=true)
public class ConversionRecoveryConfig {
    private final QuotationApplicationService service;
    public ConversionRecoveryConfig(QuotationApplicationService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.conversion-recovery.delay-ms:30000}", initialDelayString="${app.conversion-recovery.delay-ms:30000}")
    public void recover() { service.recoverPendingConversions(); }
}
