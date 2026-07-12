package jaider.ecommerce.notificacion.event;

/** El estado de un pedido cambió — se notifica al cliente dueño del pedido. */
public record PedidoEstadoCambiadoEvent(Long tndId, Long usrId, Long pedId, String numero, String estado) {}
