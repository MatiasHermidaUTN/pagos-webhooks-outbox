package com.pagos.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Procesa un webhook de forma idempotente y sin bloquear tablas.
 *
 * <ul>
 *   <li><b>Idempotencia:</b> {@code payment_event.event_id} es UNIQUE. Un reintento (mismo event_id)
 *       devuelve DUPLICATE y no vuelve a escribir nada.</li>
 *   <li><b>Orden y concurrencia:</b> el cambio de estado es un unico UPDATE condicional sobre la fila
 *       del pago ({@code WHERE status IN (origenes permitidos)}). La base serializa los eventos del
 *       mismo pago con un lock de fila y eventos de pagos distintos no se bloquean entre si.</li>
 *   <li><b>Auditoria:</b> el evento se registra siempre, aunque no cambie el estado.</li>
 * </ul>
 */
@Service
public class WebhookService {

    private static final int MAX_ATTEMPTS = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry metrics;

    public WebhookService(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction, Clock clock,
                          MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
        this.metrics = metrics;
    }

    public WebhookResponse process(WebhookRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                WebhookOutcome outcome = transaction.execute(status -> processOnce(request));
                metrics.counter("paygate.webhooks", "outcome", outcome.name()).increment();
                return new WebhookResponse(request.eventId(), outcome);
            } catch (DuplicateKeyException | ConcurrencyFailureException e) {
                // Carrera entre dos eventos del mismo pago que la base resolvio abortando uno: se reintenta entero.
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private WebhookOutcome processOnce(WebhookRequest request) {
        if (eventExists(request.eventId())) {
            return WebhookOutcome.DUPLICATE;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);

        boolean applied = createPaymentIfAbsent(request, now) || advanceStatus(request, now);
        WebhookOutcome outcome = applied ? WebhookOutcome.APPLIED : WebhookOutcome.IGNORED;

        // Si dos reintentos identicos corren a la vez, pueden pasar los dos el NOT EXISTS: el UNIQUE hace
        // fallar a uno (DuplicateKeyException), process() reintenta y lo ve como DUPLICATE. Nada se aplica dos
        // veces porque el UPDATE condicional no repite la misma transicion.
        int inserted = insertEvent(request, now, outcome);
        return inserted == 0 ? WebhookOutcome.DUPLICATE : outcome;
    }

    private boolean eventExists(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_event WHERE event_id = :eventId",
                new MapSqlParameterSource("eventId", eventId), Integer.class);
        return count != null && count > 0;
    }

    /** El primer evento que se ve de un pago lo crea con su estado. NOT EXISTS en vez de ON CONFLICT para que corra igual en H2. */
    private boolean createPaymentIfAbsent(WebhookRequest r, OffsetDateTime now) {
        return jdbc.update("""
                INSERT INTO payment (payment_id, status, amount, currency, created_at, updated_at)
                SELECT :paymentId, :status, :amount, :currency, :now, :now
                 WHERE NOT EXISTS (SELECT 1 FROM payment WHERE payment_id = :paymentId)
                """, params(r, now)) == 1;
    }

    /** Cambia el estado solo si el estado actual permite llegar al nuevo. */
    private boolean advanceStatus(WebhookRequest r, OffsetDateTime now) {
        var allowedFrom = r.status().allowedFrom();
        if (allowedFrom.isEmpty()) {
            return false;
        }
        return jdbc.update("""
                UPDATE payment
                   SET status = :status, amount = :amount, currency = :currency, version = version + 1, updated_at = :now
                 WHERE payment_id = :paymentId AND status IN (:allowedFrom)
                """, params(r, now).addValue("allowedFrom", allowedFrom)) == 1;
    }

    private int insertEvent(WebhookRequest r, OffsetDateTime now, WebhookOutcome outcome) {
        return jdbc.update("""
                INSERT INTO payment_event (event_id, payment_id, status, amount, currency,
                                           event_timestamp, received_at, outcome)
                SELECT :eventId, :paymentId, :status, :amount, :currency, :eventTimestamp, :now, :outcome
                 WHERE NOT EXISTS (SELECT 1 FROM payment_event WHERE event_id = :eventId)
                """, params(r, now).addValue("outcome", outcome.name()));
    }

    private static MapSqlParameterSource params(WebhookRequest r, OffsetDateTime now) {
        return new MapSqlParameterSource()
                .addValue("eventId", r.eventId())
                .addValue("paymentId", r.paymentId())
                .addValue("status", r.status().name())
                .addValue("amount", r.amount())
                .addValue("currency", r.currency())
                .addValue("eventTimestamp", r.timestamp())
                .addValue("now", now);
    }
}
