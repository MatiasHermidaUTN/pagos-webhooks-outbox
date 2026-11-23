package com.pagos.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Cuerpo del webhook de PayGate. */
public record WebhookRequest(
        @JsonProperty("event_id") @NotBlank @Size(max = 64) String eventId,
        @JsonProperty("payment_id") @NotBlank @Size(max = 64) String paymentId,
        @NotNull PaymentStatus status,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull OffsetDateTime timestamp) {
}
