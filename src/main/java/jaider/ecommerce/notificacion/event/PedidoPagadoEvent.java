package jaider.ecommerce.notificacion.event;

/** Un pedido fue confirmado como pagado y queda listo para preparar — se notifica a los admins de la tienda. */
public record PedidoPagadoEvent(Long tndId, Long pedId, String numero) {}
