package jaider.ecommerce.notificacion.event;

/** Un producto que estaba completamente agotado volvió a tener stock — se notifica a quienes lo tienen en su lista de deseos. */
public record StockDisponibleEvent(Long tndId, Long prdId, String nombreProducto) {}
