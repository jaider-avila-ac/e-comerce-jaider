package jaider.ecommerce.notificacion.event;

/** El admin canceló un pedido (con reembolso asociado o no) — se notifica al cliente dueño. */
public record PedidoCanceladoEvent(Long tndId, Long usrId, Long pedId, String numero,
                                    String motivoLabel, String nota) {}
