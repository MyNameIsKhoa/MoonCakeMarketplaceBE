package mss.mooncake.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import mss.mooncake.commerce.dto.request.CreateShipmentRequest;
import mss.mooncake.commerce.dto.response.ShipmentItemProgressResponse;
import mss.mooncake.commerce.dto.response.ShipmentResponse;
import mss.mooncake.commerce.enums.ShipmentStatus;
import mss.mooncake.commerce.exception.FinanceExceptionHandler;
import mss.mooncake.commerce.exception.GlobalExceptionHandler;
import mss.mooncake.commerce.service.FinanceActorResolver;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** JSON contract of shipment lines: request binding, validation and the per-item progress endpoint. */
class ShipmentControllerContractTest {
    private static final FinanceService.Actor SUPPLIER = new FinanceService.Actor(101, null, 20L, false);
    private final FulfillmentApplicationService fulfillment = mock(FulfillmentApplicationService.class);
    private final FinanceActorResolver actors = mock(FinanceActorResolver.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(actors.resolve(any())).thenReturn(SUPPLIER);
        mvc = MockMvcBuilders.standaloneSetup(new ShipmentController(fulfillment, actors))
                .setControllerAdvice(new FinanceExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test
    void createBindsLinesAndReturnsThem() throws Exception {
        when(fulfillment.createShipment(any(), eq(20L))).thenReturn(new ShipmentResponse(9L, 5L, "STANDARD", "GHN",
                "T1", null, null, null, null, BigDecimal.ZERO, ShipmentStatus.PLANNED,
                List.of(new ShipmentResponse.Line(11L, "Bánh nướng", "Hộp 4", "SKU-4", 2))));

        mvc.perform(post("/api/shipments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sellerOrderId":5,"shippingMethod":"STANDARD","provider":"GHN","trackingCode":"T1",
                         "shippingFee":0,"lines":[{"orderItemId":11,"quantity":2},{"orderItemId":12,"quantity":1}]}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].orderItemId").value(11))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].productNameSnapshot").value("Bánh nướng"));

        ArgumentCaptor<CreateShipmentRequest> request = ArgumentCaptor.forClass(CreateShipmentRequest.class);
        verify(fulfillment).createShipment(request.capture(), eq(20L));
        assertThat(request.getValue().lines()).containsExactly(new CreateShipmentRequest.Line(11L, 2),
                new CreateShipmentRequest.Line(12L, 1));
    }

    @Test
    void createWithoutLinesStillBinds() throws Exception {
        when(fulfillment.createShipment(any(), eq(20L))).thenReturn(new ShipmentResponse(9L, 5L, "STANDARD", null,
                null, null, null, null, null, BigDecimal.ZERO, ShipmentStatus.PLANNED, List.of()));
        mvc.perform(post("/api/shipments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerOrderId\":5,\"shippingMethod\":\"STANDARD\",\"shippingFee\":0}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<CreateShipmentRequest> request = ArgumentCaptor.forClass(CreateShipmentRequest.class);
        verify(fulfillment).createShipment(request.capture(), eq(20L));
        assertThat(request.getValue().lines()).isNull();
    }

    @Test
    void nonPositiveLineQuantityIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/shipments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sellerOrderId":5,"shippingMethod":"STANDARD","shippingFee":0,
                         "lines":[{"orderItemId":11,"quantity":0}]}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(fulfillment);
    }

    @Test
    void itemProgressIsReadForTheCaller() throws Exception {
        when(fulfillment.getShipmentItemProgress(5L, SUPPLIER)).thenReturn(List.of(
                new ShipmentItemProgressResponse(11L, "Bánh nướng", "Hộp 4", "SKU-4", 5, 3, 2, 1, 2)));
        mvc.perform(get("/api/shipments/items").param("sellerOrderId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderItemId").value(11))
                .andExpect(jsonPath("$[0].orderedQuantity").value(5))
                .andExpect(jsonPath("$[0].allocatedQuantity").value(3))
                .andExpect(jsonPath("$[0].shippedQuantity").value(2))
                .andExpect(jsonPath("$[0].deliveredQuantity").value(1))
                .andExpect(jsonPath("$[0].remainingQuantity").value(2));
    }
}
