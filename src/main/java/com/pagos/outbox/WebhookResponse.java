package com.pagos.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WebhookResponse(
        @JsonProperty("event_id") String eventId,
        WebhookOutcome outcome) {
}
