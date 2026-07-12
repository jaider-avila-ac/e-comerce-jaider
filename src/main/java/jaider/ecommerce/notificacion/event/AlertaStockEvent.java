package jaider.ecommerce.notificacion.event;

/** El stock se agotó entre el checkout y la confirmación del pago — requiere revisión manual del admin. */
public record AlertaStockEvent(Long tndId, Long pedId, String numero) {}
