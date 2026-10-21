package mss.mooncake.catalogstore.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult;
import mss.mooncake.catalogstore.service.PromotionCalculationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/promotions")
@Hidden
public class InternalPromotionCalculationController {
    private final PromotionCalculationService service;

    public InternalPromotionCalculationController(PromotionCalculationService service) { this.service = service; }

    @PostMapping("/calculate")
    public PromotionCalculationResult calculate(@Valid @RequestBody PromotionCalculationRequest request) {
        return service.calculate(request);
    }
}
