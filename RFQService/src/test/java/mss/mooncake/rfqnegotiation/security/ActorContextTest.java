package mss.mooncake.rfqnegotiation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ActorContextTest {
    private final IdentityServiceClient identity = mock(IdentityServiceClient.class);
    private final ActorContext actor = new ActorContext(identity);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(10L, "supplier@test", List.of()), null, List.of()));
    }

    @AfterEach
    void logout() { SecurityContextHolder.clearContext(); }

    @Test
    void verifiedSupplierMayTrade() {
        when(identity.getSupplierByUser(10L)).thenReturn(new SupplierSummaryResponse(77L, 10L, "Bakery", null, "VERIFIED"));
        assertThat(actor.verifiedSupplierId()).isEqualTo(77L);
    }

    @Test
    void suspendedSupplierMayOnlyRead() {
        when(identity.getSupplierByUser(10L)).thenReturn(new SupplierSummaryResponse(77L, 10L, "Bakery", null, "SUSPENDED"));
        assertThat(actor.supplierId()).isEqualTo(77L);
        assertThatThrownBy(actor::verifiedSupplierId)
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SUPPLIER_NOT_VERIFIED");
    }
}
