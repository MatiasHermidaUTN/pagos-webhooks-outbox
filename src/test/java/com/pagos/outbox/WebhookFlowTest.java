package com.pagos.outbox;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment_event");
        jdbc.update("DELETE FROM payment");
    }

    private static String body(String eventId, String paymentId, String status, String timestamp) {
        return "{\"event_id\":\"" + eventId + "\",\"payment_id\":\"" + paymentId + "\",\"status\":\"" + status
                + "\",\"amount\":1500.00,\"currency\":\"ARS\",\"timestamp\":\"" + timestamp + "\"}";
    }

    private ResultActions send(String eventId, String paymentId, String status, String timestamp) throws Exception {
        return mvc.perform(post("/webhooks/paygate").contentType(MediaType.APPLICATION_JSON)
                .content(body(eventId, paymentId, status, timestamp)));
    }

    private void assertStatus(String paymentId, String expected) throws Exception {
        mvc.perform(get("/payments/" + paymentId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected));
    }

    @Test
    void primerEventoCreaElPagoYDevuelve200() throws Exception {
        send("e1", "p1", "PENDING", "2023-10-25T14:30:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));

        mvc.perform(get("/payments/p1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment_id").value("p1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.currency").value("ARS"))
                .andExpect(jsonPath("$.event_count").value(1));
    }

    @Test
    void unReintentoConElMismoEventIdNoSeVuelveAAplicarNiAGuardar() throws Exception {
        send("e1", "p1", "AUTHORIZED", "2023-10-25T14:30:00Z").andExpect(jsonPath("$.outcome").value("APPLIED"));
        for (int i = 0; i < 5; i++) {
            send("e1", "p1", "AUTHORIZED", "2023-10-25T14:30:00Z")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        }

        mvc.perform(get("/payments/p1")).andExpect(jsonPath("$.event_count").value(1));
        mvc.perform(get("/payments/p1/events")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void unReintentoDeUnEventoViejoNoReviveNiDegradaElPago() throws Exception {
        send("e1", "p1", "AUTHORIZED", "2023-10-25T14:30:00Z");
        send("e2", "p1", "CAPTURED", "2023-10-25T14:31:00Z");
        // PayGate reintenta el e1 despues de que el pago ya avanzo
        send("e1", "p1", "AUTHORIZED", "2023-10-25T14:30:00Z").andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        assertStatus("p1", "CAPTURED");
        mvc.perform(get("/payments/p1/events")).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void unEventoViejoQueLlegaTardeNoDegradaElEstadoPeroQuedaEnElHistorial() throws Exception {
        send("e2", "p1", "AUTHORIZED", "2023-10-25T14:31:00Z");
        send("e3", "p1", "CAPTURED", "2023-10-25T14:32:00Z");
        // llegan despues, pero ocurrieron antes
        send("e1", "p1", "PENDING", "2023-10-25T14:30:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        send("e2b", "p1", "AUTHORIZED", "2023-10-25T14:31:30Z").andExpect(jsonPath("$.outcome").value("IGNORED"));

        assertStatus("p1", "CAPTURED");
        mvc.perform(get("/payments/p1/events"))
                .andExpect(jsonPath("$", hasSize(4)))
                // historial cronologico por timestamp del evento, no por orden de llegada
                .andExpect(jsonPath("$[0].event_id").value("e1"))
                .andExpect(jsonPath("$[0].outcome").value("IGNORED"))
                .andExpect(jsonPath("$[1].event_id").value("e2"))
                .andExpect(jsonPath("$[2].event_id").value("e2b"))
                .andExpect(jsonPath("$[3].event_id").value("e3"));
    }

    @Test
    void elPrimerEventoQueLlegaDeUnPagoPuedeNoSerPending() throws Exception {
        send("e3", "p1", "CAPTURED", "2023-10-25T14:32:00Z").andExpect(jsonPath("$.outcome").value("APPLIED"));
        send("e1", "p1", "PENDING", "2023-10-25T14:30:00Z").andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertStatus("p1", "CAPTURED");
    }

    @Test
    void unRefundQueLlegaAntesDelCapturedIgualQuedaComoEstadoFinal() throws Exception {
        send("e1", "p1", "AUTHORIZED", "2023-10-25T14:30:00Z");
        send("e3", "p1", "REFUNDED", "2023-10-25T14:33:00Z").andExpect(jsonPath("$.outcome").value("APPLIED"));
        send("e2", "p1", "CAPTURED", "2023-10-25T14:31:00Z").andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertStatus("p1", "REFUNDED");
    }

    @Test
    void failedEsTerminalYRefundedEsTerminal() throws Exception {
        send("a1", "pf", "AUTHORIZED", "2023-10-25T14:30:00Z");
        send("a2", "pf", "FAILED", "2023-10-25T14:31:00Z").andExpect(jsonPath("$.outcome").value("APPLIED"));
        send("a3", "pf", "CAPTURED", "2023-10-25T14:32:00Z").andExpect(jsonPath("$.outcome").value("IGNORED"));
        send("a4", "pf", "REFUNDED", "2023-10-25T14:33:00Z").andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertStatus("pf", "FAILED");

        send("b1", "pr", "CAPTURED", "2023-10-25T14:30:00Z");
        send("b2", "pr", "REFUNDED", "2023-10-25T14:31:00Z").andExpect(jsonPath("$.outcome").value("APPLIED"));
        send("b3", "pr", "FAILED", "2023-10-25T14:32:00Z").andExpect(jsonPath("$.outcome").value("IGNORED"));
        send("b4", "pr", "CAPTURED", "2023-10-25T14:33:00Z").andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertStatus("pr", "REFUNDED");
    }

    @Test
    void unCapturedYaProcesadoNoPasaAFailed() throws Exception {
        send("c1", "pc", "CAPTURED", "2023-10-25T14:30:00Z");
        send("c2", "pc", "FAILED", "2023-10-25T14:31:00Z").andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertStatus("pc", "CAPTURED");
    }

    @Test
    void pagosDistintosNoSeAfectanEntreSi() throws Exception {
        send("x1", "pa", "CAPTURED", "2023-10-25T14:30:00Z");
        send("y1", "pb", "PENDING", "2023-10-25T14:30:00Z");
        assertStatus("pa", "CAPTURED");
        assertStatus("pb", "PENDING");
    }

    @Test
    void unWebhookInvalidoDevuelve400YNoGuardaNada() throws Exception {
        mvc.perform(post("/webhooks/paygate").contentType(MediaType.APPLICATION_JSON)
                .content(body("e1", "p1", "CANCELLED", "2023-10-25T14:30:00Z"))).andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/paygate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_id\":\"p1\",\"status\":\"PENDING\",\"amount\":10,\"currency\":\"ARS\","
                        + "\"timestamp\":\"2023-10-25T14:30:00Z\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/paygate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\":\"e1\",\"payment_id\":\"p1\",\"status\":\"PENDING\",\"amount\":-5,"
                        + "\"currency\":\"ARS\",\"timestamp\":\"2023-10-25T14:30:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/payments/p1")).andExpect(status().isNotFound());
    }

    @Test
    void consultarUnPagoInexistenteDevuelve404() throws Exception {
        mvc.perform(get("/payments/no-existe")).andExpect(status().isNotFound());
        mvc.perform(get("/payments/no-existe/events")).andExpect(status().isNotFound());
    }
}
