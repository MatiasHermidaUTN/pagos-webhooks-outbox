package com.pagos.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Un evento del historial de un pago. */
public record PaymentEventView(
        @JsonProperty("event_id") String eventId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        @JsonProperty("event_timestamp") OffsetDateTime eventTimestamp,
        @JsonProperty("received_at") OffsetDateTime receivedAt,
        WebhookOutcome outcome) {
}
