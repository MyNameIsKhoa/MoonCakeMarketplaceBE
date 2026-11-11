package mss.mooncake.identity.controller;

import jakarta.validation.Valid;
import java.util.List;
import mss.mooncake.identity.dto.request.AddressRequest;
import mss.mooncake.identity.dto.request.UpdateAddressRequest;
import mss.mooncake.identity.dto.response.AddressResponse;
import mss.mooncake.identity.exception.UnauthorizedException;
import mss.mooncake.identity.security.UserPrincipal;
import mss.mooncake.identity.service.AddressApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressApplicationService addressService;

    public AddressController(AddressApplicationService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public ResponseEntity<List<AddressResponse>> getMyAddresses(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("Vui lòng đăng nhập");
        }
        return ResponseEntity.ok(addressService.getCustomerAddresses(principal.getUserId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AddressResponse> getAddress(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable("id") Long id) {
        if (principal == null) {
            throw new UnauthorizedException("Vui lòng đăng nhập");
        }
        return ResponseEntity.ok(addressService.getCustomerAddress(principal.getUserId(), id));
    }

    @PostMapping
    public ResponseEntity<AddressResponse> createAddress(@AuthenticationPrincipal UserPrincipal principal,
                                                         @Valid @RequestBody AddressRequest request) {
        if (principal == null) {
            throw new UnauthorizedException("Vui lòng đăng nhập");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(addressService.createAddress(principal.getUserId(), request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AddressResponse> updateAddress(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable("id") Long id,
                                                         @Valid @RequestBody UpdateAddressRequest request) {
        if (principal == null) {
            throw new UnauthorizedException("Vui lòng đăng nhập");
        }
        return ResponseEntity.ok(addressService.updateAddress(principal.getUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAddress(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable("id") Long id) {
        if (principal == null) {
            throw new UnauthorizedException("Vui lòng đăng nhập");
        }
        addressService.deleteAddress(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
