package mss.mooncake.rfqnegotiation.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
public record CreateRfqRequest(@NotNull Long customerId, @NotBlank String title, String description,
                               @NotNull Integer quantity, BigDecimal budgetAmount, LocalDate requestedDeliveryDate,
                               String deliveryLocation, String customizationRequirements) { }
