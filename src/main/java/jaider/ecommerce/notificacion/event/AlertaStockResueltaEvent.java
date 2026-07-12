package jaider.ecommerce.notificacion.event;

/** El admin marcó como resuelta una alerta de stock insuficiente. */
public record AlertaStockResueltaEvent(Long tndId, Long pedId, String numero) {}
