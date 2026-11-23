package com.pagos.outbox;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    /** Responde 200 para eventos nuevos, ignorados y reintentos: PayGate solo reintenta si no recibe 2xx. */
    @PostMapping("/webhooks/paygate")
    public WebhookResponse receive(@Valid @RequestBody WebhookRequest request) {
        return service.process(request);
    }
}
