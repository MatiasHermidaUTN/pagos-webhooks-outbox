package com.pagos.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Propiedad central del servicio: el resultado no depende del orden de llegada. Se prueban todas las
 * permutaciones posibles de un mismo conjunto de eventos y todas tienen que terminar en el mismo estado.
 */
@SpringBootTest
class ConvergenceTest {

    @Autowired
    WebhookService service;
    @Autowired
    PaymentQueryService queries;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment_event");
        jdbc.update("DELETE FROM payment");
    }

    private static WebhookRequest event(String id, String paymentId, PaymentStatus status, int minute) {
        return new WebhookRequest(id, paymentId, status, new BigDecimal("2500.50"), "ARS",
                OffsetDateTime.parse("2023-10-25T14:00:00Z").plusMinutes(minute));
    }

    private static <T> void permutations(List<T> items, int k, List<List<T>> out) {
        if (k == items.size()) {
            out.add(new ArrayList<>(items));
            return;
        }
        for (int i = k; i < items.size(); i++) {
            java.util.Collections.swap(items, k, i);
            permutations(items, k + 1, out);
            java.util.Collections.swap(items, k, i);
        }
    }

    @Test
    void ciclo_completo_converge_a_REFUNDED_en_las_24_permutaciones() {
        List<PaymentStatus> flow = List.of(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED,
                PaymentStatus.CAPTURED, PaymentStatus.REFUNDED);
        List<List<Integer>> orders = new ArrayList<>();
        permutations(new ArrayList<>(List.of(0, 1, 2, 3)), 0, orders);
        assertThat(orders).hasSize(24);

        int run = 0;
        for (List<Integer> order : orders) {
            String paymentId = "ciclo-" + run++;
            for (int idx : order) {
                service.process(event(paymentId + "-e" + idx, paymentId, flow.get(idx), idx));
            }
            assertThat(queries.getPayment(paymentId).status())
                    .as("orden %s", order).isEqualTo(PaymentStatus.REFUNDED);
            // todos los eventos quedan en el historial, en orden cronologico
            assertThat(queries.getEvents(paymentId)).hasSize(4)
                    .extracting(PaymentEventView::status).containsExactlyElementsOf(flow);
        }
    }

    @Test
    void camino_fallido_converge_a_FAILED_en_las_6_permutaciones() {
        List<PaymentStatus> flow = List.of(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED, PaymentStatus.FAILED);
        List<List<Integer>> orders = new ArrayList<>();
        permutations(new ArrayList<>(List.of(0, 1, 2)), 0, orders);

        int run = 0;
        for (List<Integer> order : orders) {
            String paymentId = "fallo-" + run++;
            for (int idx : order) {
                service.process(event(paymentId + "-e" + idx, paymentId, flow.get(idx), idx));
            }
            assertThat(queries.getPayment(paymentId).status())
                    .as("orden %s", order).isEqualTo(PaymentStatus.FAILED);
        }
    }

    @Test
    void la_version_sube_solo_cuando_el_estado_cambia() {
        service.process(event("v1", "pv", PaymentStatus.PENDING, 0));
        assertThat(queries.getPayment("pv").version()).isEqualTo(1);

        service.process(event("v2", "pv", PaymentStatus.AUTHORIZED, 1));
        service.process(event("v3", "pv", PaymentStatus.CAPTURED, 2));
        assertThat(queries.getPayment("pv").version()).isEqualTo(3);

        // duplicado y evento viejo: no cambian la version
        service.process(event("v3", "pv", PaymentStatus.CAPTURED, 2));
        service.process(event("v0", "pv", PaymentStatus.PENDING, 0));
        assertThat(queries.getPayment("pv").version()).isEqualTo(3);
        assertThat(queries.getPayment("pv").eventCount()).isEqualTo(4);
    }
}
