# Decisiones y dudas (v1)

Primera versión hecha **antes** de hablar con el Product Owner, con lo que dice el enunciado. Lo que no sé lo
asumo y lo anoto acá para preguntarlo.

## Supuestos de esta versión

| # | Supuesto | Por qué lo asumo |
|---|---|---|
| S1 | Un reintento de PayGate puede traer un `event_id` distinto | El enunciado dice "reintenta envíos" pero no dice si el id se repite |
| S2 | Los duplicados se detectan por `(payment_id, status, timestamp)` | Consecuencia de S1: no puedo confiar en `event_id` como clave |
| S3 | Gana siempre el evento con el `timestamp` más nuevo | Es la regla más simple para el desorden |
| S4 | Cualquier transición de estado es válida | No hay una máquina de estados en el enunciado |
| S5 | Un evento viejo se ignora y queda en el historial | Para auditar |

## Dudas para el Product Owner

1. ¿El `event_id` se mantiene igual cuando PayGate reintenta? (define la clave de idempotencia, S1 y S2)
2. ¿Cuántos reintentos hace y con qué timeout? ¿Qué respuesta espera ante un duplicado?
3. ¿Qué garantías de orden da PayGate? ¿Hay una regla de negocio para un evento viejo? (S3 y S5)
4. ¿Cuáles son las transiciones válidas entre estados? (S4)
5. ¿Los eventos de un mismo pago pueden llegar en paralelo?
6. ¿Qué motor de base usan y qué volumen esperan?
7. ¿`amount` y `currency` pueden cambiar entre eventos (reembolsos parciales)?

## Respuestas del agente

_Pendiente: todavía no hablé con el Product Owner._

## Dudas abiertas

Las 7 de arriba.
