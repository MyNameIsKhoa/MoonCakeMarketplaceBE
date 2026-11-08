package mss.mooncake.rfqnegotiation.controller;

import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/rfq")
public class AdminRfqMetricsController {
    public record Metrics(long rfqs) { }

    private final RfqRepository rfqs;

    public AdminRfqMetricsController(RfqRepository rfqs) { this.rfqs = rfqs; }

    @GetMapping("/metrics")
    public Metrics metrics() { return new Metrics(rfqs.count()); }
}
