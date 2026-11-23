package com.pagos.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentQueryService {

    private final JdbcTemplate jdbc;

    public PaymentQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PaymentView getPayment(String paymentId) {
        List<PaymentView> found = jdbc.query("""
                SELECT p.payment_id, p.status, p.amount, p.currency, p.version, p.updated_at,
                       (SELECT COUNT(*) FROM payment_event e WHERE e.payment_id = p.payment_id) AS event_count
                  FROM payment p
                 WHERE p.payment_id = ?
                """, (rs, i) -> new PaymentView(
                rs.getString("payment_id"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getInt("event_count")), paymentId);
        if (found.isEmpty()) {
            throw new PaymentNotFoundException(paymentId);
        }
        return found.get(0);
    }

    /** Historial cronologico: por el timestamp del evento y, a igual timestamp, por orden de llegada. */
    public List<PaymentEventView> getEvents(String paymentId) {
        getPayment(paymentId);
        return jdbc.query("""
                SELECT event_id, status, amount, currency, event_timestamp, received_at, outcome
                  FROM payment_event
                 WHERE payment_id = ?
                 ORDER BY event_timestamp, id
                """, (rs, i) -> new PaymentEventView(
                rs.getString("event_id"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getObject("event_timestamp", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),
                WebhookOutcome.valueOf(rs.getString("outcome"))), paymentId);
    }
}
