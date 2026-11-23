# pagos-webhooks-outbox

Receptor de webhooks de **PayGate** con una **bandeja de eventos** (`payment_event`): cada notificación recibida
se registra, se decide si cambia el estado del pago y queda guardado con su resultado. Tolera reintentos, entregas
fuera de orden y eventos concurrentes del mismo pago sin dejar estados inválidos ni acreditar dos veces.

Java 17 · Spring Boot 3 · JDBC · Flyway · PostgreSQL (H2 en las pruebas) · Actuator.

> El proceso de construcción está en [`docs/DECISIONES_Y_DUDAS.md`](docs/DECISIONES_Y_DUDAS.md): una v1 con
> supuestos propios, las dudas que se le hicieron al Product Owner, y cómo cada respuesta corrigió el diseño.

## Ejecutarlo

```bash
docker compose up --build        # PostgreSQL + el servicio
# o, local con Java 17 y Maven:
docker compose up -d db && mvn spring-boot:run
```

Variables opcionales: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE` (20). Flyway crea el esquema al arrancar.
Contrato completo en [`openapi.yaml`](openapi.yaml).

```bash
curl -X POST localhost:8080/webhooks/paygate -H 'Content-Type: application/json' -d '{
  "event_id": "7c1f0a52-0d1a-4a52-9a3e-1b0c9f3b0001", "payment_id": "a8d3f9b2-6a0e-4e53-8c11-5b6a7e2d0002",
  "status": "AUTHORIZED", "amount": 1500.00, "currency": "ARS", "timestamp": "2023-10-25T14:30:00Z" }'
curl localhost:8080/payments/a8d3f9b2-6a0e-4e53-8c11-5b6a7e2d0002
curl localhost:8080/payments/a8d3f9b2-6a0e-4e53-8c11-5b6a7e2d0002/events
```

## Probarlo

```bash
mvn test        # 17 pruebas, sin Docker (H2 en modo PostgreSQL con las mismas migraciones)
```

- `WebhookFlowTest`: alta, duplicados y reintentos, evento viejo tardío, estados terminales, payloads inválidos y
  pagos inexistentes.
- `ConvergenceTest`: las 24 permutaciones del ciclo completo y las 6 del camino fallido terminan en el mismo estado.
- `ConcurrencyTest`: eventos del mismo pago desordenados y repetidos desde 12 hilos, 8 hilos con el mismo
  `event_id` y 30 pagos en paralelo.

## API

| Método | Ruta | Respuesta |
|---|---|---|
| `POST` | `/webhooks/paygate` | **200** `{event_id, outcome}`: `APPLIED`, `IGNORED` o `DUPLICATE`; **400** si es inválido |
| `GET` | `/payments/{id}` | Estado consolidado, monto, moneda, `version` y cantidad de eventos; **404** si no existe |
| `GET` | `/payments/{id}/events` | Historial cronológico (por `timestamp` del evento) con el `outcome` de cada uno |

Los duplicados y los eventos ignorados también devuelven 200: PayGate reintenta mientras no reciba un 2xx.

## Diseño

**La bandeja de eventos.** `payment_event` guarda *todo* evento recibido, con su `outcome` (`APPLIED` o
`IGNORED`). Es a la vez el historial para auditoría y la fuente de verdad para reconstruir un pago.
`event_id` es `UNIQUE`: PayGate mantiene el mismo `event_id` en cada reintento, así que un evento ya registrado se
devuelve como `DUPLICATE` sin escribir ni cambiar nada.

**Estado por máquina de estados, no por orden de llegada.** El cambio es un único `UPDATE` condicional por fila:

```sql
UPDATE payment SET status = :nuevo, ..., version = version + 1
 WHERE payment_id = :id AND status IN (:orígenes permitidos)
```

| Evento | Se aplica si el pago está en |
|---|---|
| `PENDING` | solo crea el pago |
| `AUTHORIZED` | `PENDING` |
| `CAPTURED` | `PENDING`, `AUTHORIZED` |
| `FAILED` | `PENDING`, `AUTHORIZED` |
| `REFUNDED` | `PENDING`, `AUTHORIZED`, `CAPTURED` |

`FAILED` y `REFUNDED` son terminales; un evento viejo nunca degrada el estado y queda en la bandeja como
`IGNORED`. El resultado es el mismo para cualquier orden de llegada.

**Concurrencia sin bloquear tablas.** El `UPDATE` condicional toma un lock **de fila**: los eventos del mismo pago
se serializan y los de pagos distintos no se esperan. Crear el pago y registrar el evento usan
`INSERT ... WHERE NOT EXISTS` más los índices únicos; si dos hilos compiten, la base hace fallar a uno y el
servicio reintenta la transacción completa (hasta 3 veces).

**Observabilidad.** `/actuator/health` y el contador `paygate.webhooks` con el tag `outcome`.

## Supuestos a validar

- **Matriz de transiciones:** Mario no la definió. `CAPTURED → FAILED` se ignora y `CAPTURED → REFUNDED` se
  permite aunque ambos figuren como finales.
- **Reembolsos parciales:** no se sabe si `amount` varía entre eventos. El pago toma el monto del último evento
  aplicado y cada evento guarda el suyo; con reembolsos parciales, `REFUNDED` debería guardar el monto reembolsado
  aparte.
- **Concurrencia por pago:** se asume que PayGate puede enviar eventos del mismo pago en paralelo.

## Escalabilidad

Cada request son 3 a 4 sentencias cortas sobre claves primarias o únicas, sin llamadas externas, dentro del
timeout de 2 s. A 800 req/s el primer límite es el pool de conexiones (`DB_POOL_SIZE`) frente al máximo de
PostgreSQL, y se escala con más réplicas porque la corrección vive en la base, no en memoria. Para volúmenes
mayores se puede persistir el evento y responder 2xx de inmediato, aplicándolo de forma asíncrona (de ahí el
nombre *outbox*); la idempotencia por `event_id` y el `UPDATE` condicional ya lo soportan. Las pruebas de
concurrencia corren en H2: conviene repetirlas contra PostgreSQL real.
