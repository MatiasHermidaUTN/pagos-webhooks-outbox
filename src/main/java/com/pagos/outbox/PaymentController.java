package com.pagos.outbox;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentQueryService queries;

    public PaymentController(PaymentQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentView get(@PathVariable String paymentId) {
        return queries.getPayment(paymentId);
    }

    @GetMapping("/payments/{paymentId}/events")
    public List<PaymentEventView> events(@PathVariable String paymentId) {
        return queries.getEvents(paymentId);
    }
}
