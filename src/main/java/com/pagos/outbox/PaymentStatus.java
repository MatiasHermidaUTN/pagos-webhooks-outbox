package com.pagos.outbox;

import java.util.List;

/**
 * Estados de un pago y de donde se puede llegar a cada uno.
 *
 * <p>PayGate no garantiza el orden de entrega, asi que el estado nunca se decide por el orden
 * de llegada sino por esta matriz: un evento solo cambia el pago si el estado actual esta entre
 * los "origenes" permitidos de su estado destino. Un evento que llega tarde (por ejemplo un
 * PENDING despues de un CAPTURED) no esta permitido, no degrada nada y queda solo en el historial.
 */
public enum PaymentStatus {
    /** Solo se llega creando el pago: ningun estado vuelve a PENDING. */
    PENDING(),
    AUTHORIZED("PENDING"),
    CAPTURED("PENDING", "AUTHORIZED"),
    /** Terminal: ningun estado sale de FAILED, ni siquiera un CAPTURED que llegue tarde. */
    FAILED("PENDING", "AUTHORIZED"),
    /**
     * Terminal. Gana sobre PENDING, AUTHORIZED y CAPTURED: como PayGate no garantiza el orden, un
     * REFUNDED puede llegar antes que el CAPTURED que lo precede y igual tiene que quedar como estado final.
     */
    REFUNDED("PENDING", "AUTHORIZED", "CAPTURED");

    private final List<String> allowedFrom;

    PaymentStatus(String... allowedFrom) {
        this.allowedFrom = List.of(allowedFrom);
    }

    /** Nombres de los estados desde los que este se puede alcanzar. */
    public List<String> allowedFrom() {
        return allowedFrom;
    }
}
