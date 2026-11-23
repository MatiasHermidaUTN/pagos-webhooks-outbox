package com.pagos.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Estado consolidado actual de un pago. */
public record PaymentView(
        @JsonProperty("payment_id") String paymentId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        long version,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("event_count") int eventCount) {
}
