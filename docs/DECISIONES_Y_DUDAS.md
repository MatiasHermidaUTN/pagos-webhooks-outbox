# Decisiones y dudas

La v1 se hizo **antes** de hablar con el Product Owner, con lo que dice el enunciado; lo que no sabía lo asumí y
lo anoté para preguntarlo. La v2 corrige esos supuestos con lo que respondió Mario. Este documento es el registro
de ese cambio.

## Supuestos de la v1 y qué pasó con cada uno

| # | Supuesto de la v1 | Respuesta del agente | En la v2 |
|---|---|---|---|
| S1 | Un reintento puede traer un `event_id` distinto | **Falso**: en cada reintento el `event_id` es idéntico | El `event_id` es la clave de idempotencia (`UNIQUE`) |
| S2 | Duplicados por `(payment_id, status, timestamp)` | Innecesario por lo anterior | Se elimina; deduplica solo por `event_id` |
| S3 | Gana siempre el `timestamp` más nuevo | Un evento viejo **no degrada** un estado que ya avanzó, pero queda en el historial | Gana el estado más avanzado de una máquina de estados, no el último en llegar |
| S4 | Cualquier transición es válida | Mario no tiene la matriz fina; los finales no se degradan | Matriz propia (ver README); `FAILED` y `REFUNDED` terminales |
| S5 | El evento viejo se ignora y se registra | **Confirmado** | Se mantiene: `outcome = IGNORED` |

La v1 también respondía distinto a un duplicado; ahora responde **200 sin reaplicar** (`DUPLICATE`), que es lo que
espera PayGate.

## Respuestas del agente (Mario, Product Owner)

- **Reintentos:** hasta 5, con backoff exponencial, si no recibe un 2xx en menos de 2 segundos. El `event_id` se
  mantiene idéntico y ante un duplicado ya procesado hay que responder HTTP 200 sin volver a aplicar el cambio.
- **Orden:** PayGate no garantiza el orden de entrega. Un evento viejo no degrada un estado avanzado (por ejemplo,
  un `PENDING` después de un `CAPTURED`) y debe quedar en el historial para auditoría.
- **Estados:** `PENDING`, `AUTHORIZED`, `CAPTURED`, `FAILED` y `REFUNDED`; los tres últimos son finales. No hay una
  máquina de estados más detallada.
- **Paralelismo:** es un escenario real; pueden llegar eventos del mismo pago casi al mismo tiempo.
- **Infraestructura:** el motor SQL es una decisión técnica nuestra. Unas 350 peticiones por segundo en horas pico y
  ráfagas de hasta 800. Hay que evitar bloqueos pesados de tabla completa.
- **Monto y moneda:** no sabe si pueden variar (reembolsos parciales); lo tiene que chequear con el proveedor. Pidió
  documentar el supuesto en el README.
- **Payload:** siempre `event_id`, `payment_id`, `status`, `amount`, `currency` y `timestamp`.

## Decisiones propias (para validar con el equipo)

1. **Matriz de transiciones.** Mario no la definió. Se eligió: `AUTHORIZED` solo desde `PENDING`; `CAPTURED` y
   `FAILED` desde `PENDING` o `AUTHORIZED`; `REFUNDED` desde `PENDING`, `AUTHORIZED` o `CAPTURED`. Así un
   `REFUNDED` que llega antes que su `CAPTURED` igual queda como estado final.
2. **`CAPTURED` → `FAILED`.** Se ignora y queda en el historial.
3. **Reembolsos parciales.** Mientras no se confirme, el pago toma `amount` y `currency` del último evento aplicado
   y cada evento guarda los suyos.

## Dudas abiertas

- ¿La matriz de transiciones es la correcta, en particular `CAPTURED` → `REFUNDED` y `CAPTURED` → `FAILED`?
- ¿`amount` y `currency` pueden variar entre eventos del mismo pago? Si hay reembolsos parciales, `REFUNDED` debería
  guardar el monto reembolsado aparte.
- ¿PayGate sincroniza los envíos concurrentes del mismo pago? Se asume que no.
