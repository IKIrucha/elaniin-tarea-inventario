# Decisiones

## Qué hay en este PR

Una implementación en memoria de `InventoryService` que reserva unidades mientras el cliente paga, las libera si no paga a tiempo y avisa a compras cuando un producto se queda con poco stock.

| Dónde | Qué |
| --- | --- |
| `reservation/CategoryPolicies` | La tabla de reglas por categoría. Es el único archivo que hay que tocar cuando marketing crea una categoría. |
| `reservation/ReservationPolicy` | Las reglas de una categoría: tiempo para pagar y límite por pedido. |
| `reservation/ReservationService` | El flujo: reservar, confirmar, liberar lo vencido, avisar. |
| `reservation/ProductStock` | Los contadores de un producto y la regla de "no repetir el aviso". |
| `Inventory` | Conecta lo anterior con las reglas de la tienda y el umbral de 5 unidades. |

El paquete `api` y la firma de `Inventory.create` no cambiaron. No se agregaron dependencias.

## Cómo se verificó

`mvn test` con Java 21: 156 ejecuciones, todas pasan, incluidos los 3 tests originales.

- Los tests de comportamiento usan solo el contrato, a través de `Inventory.create`, con un reloj que el test mueve a mano. No hay `sleep` ni dependencia de la hora real.
- `ConcurrencyTest` lanza 300 pedidos desde 16 hilos contra 50 unidades y se repite 20 veces por caso. Para comprobar que detecta carreras, quité el lock temporalmente: fallaron 51 de las 80 ejecuciones. Con el lock pasan todas.

## Supuestos

El README y el contrato dejan abiertos varios casos. Esto es lo que asumí; cada uno tiene un test con su nombre.

**Reenvíos del pedido**

- Reenviar un pedido que tiene una reserva pendiente devuelve esa misma reserva, con su vencimiento original. No reserva de nuevo ni extiende el plazo.
- Reenviar un pedido ya pagado también devuelve la reserva original. Un reintento que llega tarde no debe vender dos veces.
- Reenviar un `orderId` con otro producto u otra cantidad lanza `IllegalStateException`. El contrato no lo contempla; preferí que el error de la app sea visible a devolver en silencio una reserva que no coincide con lo que pidió.
- Si la reserva ya venció, el mismo `orderId` reserva desde cero, con plazo nuevo y sujeto al stock que haya en ese momento.
- Un pedido rechazado (sin stock o sobre el límite) no deja rastro: puede reintentarse más tarde.

**Vencimiento**

- La reserva está activa hasta justo antes de `expiresAt`. En el instante exacto de `expiresAt` ya está liberada.
- Un pago que llega después del vencimiento falla con `IllegalStateException`, aunque todavía haya stock. Ver "Antes de producción".
- El plazo se fija al reservar. Si el producto cambia de categoría después, las reservas existentes conservan el suyo.

**Confirmación**

- Confirmar dos veces el mismo pedido lanza `IllegalStateException` la segunda vez, siguiendo la letra del contrato ("si el pedido no tiene una reserva activa"). El stock no cambia. El mensaje distingue este caso ("is already confirmed") del de un pedido que nunca reservó o cuya reserva venció, para que quien llama sepa que el pago ya estaba registrado. Preferí un error explícito a un no-op silencioso; si la pasarela de pagos reintenta sus notificaciones, habría que revisarlo con ese equipo.

**Validación**

- Cuando un pedido supera el límite de la categoría y además no hay stock, se informa el límite (`OrderLimitExceededException`): es el error que el cliente no puede resolver esperando.
- Registrar de nuevo un producto existente cambia su categoría para las reservas futuras y conserva stock y reservas. Así un producto puede entrar y salir de una categoría de temporada.

**Avisos de stock bajo**

- El aviso se evalúa al reservar, que es lo único que hace bajar las unidades disponibles, e informa las unidades disponibles después de esa reserva.
- "Mientras no se reabastezca" lo tomé literal: después de un aviso no hay otro hasta el siguiente `addStock`, aunque en el medio venzan reservas y las unidades vuelvan a bajar.
- `addStock` no avisa por sí mismo. Si después de reabastecer el producto sigue en 5 o menos, el aviso sale con la siguiente reserva.
- El aviso se entrega en el mismo hilo que reserva, pero fuera del lock, para que un canal lento no frene a otros clientes. Si el canal falla, la reserva igual se completa y el error queda en el log.
- Un aviso que el canal no pudo entregar se reintenta con el siguiente pedido de ese producto, sea aceptado o rechazado, hasta que se entregue. Los pedidos rechazados cuentan porque un producto agotado solo recibe rechazos, y es justo el caso en que compras más necesita enterarse. Si mientras tanto las unidades volvieron a subir de 5 (vencieron reservas), el reintento espera a que bajen de nuevo; si el producto se reabasteció, el aviso viejo se descarta.

## Decisiones de diseño

**Un solo lock para todo el estado.** Cada operación es atómica y es fácil de razonar que no hay sobreventa. Las operaciones son accesos a mapas en memoria, así que el lock se retiene microsegundos. No usé un lock por producto porque el `orderId` es único entre productos y eso obliga a coordinar dos locks; es complejidad que se va a tirar cuando el estado pase a la base de datos.

**El vencimiento no depende de un timer.** Antes de cada operación se liberan las reservas cuyo plazo ya pasó según el `Clock`. El resultado depende solo de la hora de la llamada, no de si un proceso en segundo plano alcanzó a correr. También es lo que hace que los tests sean deterministas.

**Categoría nueva = una línea.** `CategoryPolicies.forCategory` es un `switch` sin `default`: si alguien agrega un valor al enum y olvida su regla, el proyecto no compila. El servicio recibe las reglas desde fuera, así que una categoría nueva no toca el flujo de reservas.

**Más canales de aviso sin tocar el servicio.** El servicio conoce un solo `StockAlertListener`. Para sumar canales basta pasarle a `Inventory.create` un listener que reparta a varios. No lo construí porque hoy hay un solo canal.

## Lo que dejé fuera

- **Persistencia y varias instancias.** El estado vive en memoria de un proceso. Con dos instancias, cada una tendría su propio inventario y habría sobreventa.
- **Cancelar una reserva.** El contrato no tiene esa operación; la reserva de un carrito abandonado se libera sola al vencer.
- **Devoluciones y ajustes de stock a la baja.** Solo existe `addStock`.
- **Limpieza de pedidos pagados.** Se guardan para siempre para reconocer reintentos, así que la memoria crece con las ventas. Las reservas vencidas sí se eliminan.
- **Reintento de avisos en segundo plano.** El reintento depende de que llegue otro pedido del producto: si nadie más intenta comprarlo, el aviso fallido no vuelve a salir. Mientras el canal esté caído, cada pedido de ese producto paga un intento de envío.
- **Métricas y trazas.** Solo hay un log de error cuando un aviso no se entrega.
- **Validación de identificadores.** `null` se rechaza; un `sku` u `orderId` vacío se acepta.

## Antes de producción

1. **Mover el estado a la base de datos.** El lock se reemplaza por una transacción por pedido que bloquea la fila del producto (o un `UPDATE` condicional sobre las unidades disponibles) y por una restricción de unicidad sobre `orderId`, que es la que da la idempotencia entre instancias. Las reservas vencidas se excluyen comparando `expires_at` con la hora de la base de datos, no con el reloj de cada instancia.
2. **Avisos confiables.** Guardar el aviso pendiente en la misma transacción que la reserva y enviarlo desde un proceso aparte con reintentos (outbox). Eso quita el envío del camino del cliente y hace que el reintento no dependa de que llegue otro pedido.
3. **Acordar con pagos qué pasa cuando el pago llega tarde.** Hoy `confirm` falla y el cliente queda con el dinero cobrado y sin producto. Alguien tiene que reembolsar o intentar reservar de nuevo; es una decisión de negocio.
4. **Acordar si `confirm` debe ser idempotente**, según cómo reintente la pasarela.
5. **Revisar el límite de `FLASH_SALE`.** Es por pedido, y cada producto del carrito es un pedido: el mismo cliente puede enviar varios pedidos de 2 unidades. Si la intención es limitar por cliente, el contrato necesita saber quién compra.
6. **Reglas editables sin desplegar.** Si marketing cambia plazos o límites con frecuencia, mover la tabla de `CategoryPolicies` a configuración.
7. **Métricas**: reservas vencidas, pedidos rechazados por stock y avisos fallidos, para ver la temporada alta mientras ocurre.
