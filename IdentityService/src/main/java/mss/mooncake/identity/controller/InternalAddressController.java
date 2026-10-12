package mss.mooncake.identity.controller;

import mss.mooncake.identity.dto.response.AddressSummaryResponse;
import mss.mooncake.identity.service.AddressApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/addresses")
public class InternalAddressController {

    private final AddressApplicationService addressService;

    public InternalAddressController(AddressApplicationService addressService) {
        this.addressService = addressService;
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<AddressSummaryResponse> getSummary(@PathVariable("id") Long id) {
        return ResponseEntity.ok(addressService.getAddressSummary(id));
    }
}
