package com.pagos.outbox;

/** Que paso con un webhook recibido. En los tres casos la respuesta HTTP es 200. */
public enum WebhookOutcome {
    /** El evento cambio (o creo) el estado del pago. */
    APPLIED,
    /** Evento nuevo pero que no corresponde aplicar (llego tarde o ya hay un estado final). Queda en el historial. */
    IGNORED,
    /** Reintento de un event_id ya procesado: no se vuelve a aplicar ni a registrar. */
    DUPLICATE
}
