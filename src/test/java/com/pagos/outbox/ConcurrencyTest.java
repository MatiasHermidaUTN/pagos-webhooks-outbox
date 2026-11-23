package com.pagos.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Eventos del mismo pago llegando a la vez, desordenados y repetidos. */
@SpringBootTest
class ConcurrencyTest {

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
        return new WebhookRequest(id, paymentId, status, new BigDecimal("1500.00"), "ARS",
                OffsetDateTime.parse("2023-10-25T14:00:00Z").plusMinutes(minute));
    }

    @Test
    void eventosDesordenadosYRepetidosDelMismoPagoTerminanEnElEstadoMasAvanzadoSinDuplicar() throws Exception {
        List<WebhookRequest> requests = new ArrayList<>();
        for (int copy = 0; copy < 6; copy++) {
            requests.add(event("e1", "p1", PaymentStatus.PENDING, 0));
            requests.add(event("e2", "p1", PaymentStatus.AUTHORIZED, 1));
            requests.add(event("e3", "p1", PaymentStatus.CAPTURED, 2));
            requests.add(event("e4", "p1", PaymentStatus.REFUNDED, 3));
        }
        Collections.shuffle(requests);

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WebhookResponse>> results = new ArrayList<>();
        for (WebhookRequest request : requests) {
            results.add(pool.submit(() -> {
                start.await();
                return service.process(request);
            }));
        }
        start.countDown();
        for (Future<WebhookResponse> result : results) {
            result.get(); // si alguno lanzo una excepcion, el test falla aca
        }
        pool.shutdown();

        assertThat(queries.getPayment("p1").status()).isEqualTo(PaymentStatus.REFUNDED);
        // 4 event_id distintos: cada uno se guardo una sola vez aunque llegue 6 veces
        assertThat(queries.getEvents("p1")).extracting(PaymentEventView::eventId)
                .containsExactly("e1", "e2", "e3", "e4");
        assertThat(queries.getPayment("p1").eventCount()).isEqualTo(4);
    }

    @Test
    void ochoHilosConElMismoEventIdAplicanElCambioUnaSolaVez() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WebhookResponse>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.process(event("dup", "p2", PaymentStatus.CAPTURED, 5));
            }));
        }
        start.countDown();
        int applied = 0;
        for (Future<WebhookResponse> result : results) {
            if (result.get().outcome() == WebhookOutcome.APPLIED) {
                applied++;
            }
        }
        pool.shutdown();

        assertThat(applied).isEqualTo(1);
        assertThat(queries.getPayment("p2").eventCount()).isEqualTo(1);
    }

    @Test
    void pagosDistintosEnParaleloNoSeBloqueanNiSeMezclan() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WebhookResponse>> results = new ArrayList<>();
        for (int p = 0; p < 30; p++) {
            String paymentId = "pago-" + p;
            results.add(pool.submit(() -> {
                start.await();
                return service.process(event("a-" + paymentId, paymentId, PaymentStatus.AUTHORIZED, 1));
            }));
            results.add(pool.submit(() -> {
                start.await();
                return service.process(event("c-" + paymentId, paymentId, PaymentStatus.CAPTURED, 2));
            }));
        }
        start.countDown();
        for (Future<WebhookResponse> result : results) {
            result.get();
        }
        pool.shutdown();

        for (int p = 0; p < 30; p++) {
            assertThat(queries.getPayment("pago-" + p).status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(queries.getPayment("pago-" + p).eventCount()).isEqualTo(2);
        }
    }
}
