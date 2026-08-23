package jaider.ecommerce.notificacion.event;

/** Un cliente hizo una pregunta sobre un producto — se notifica a los admins de la tienda. */
public record PreguntaCreadaEvent(Long tndId, Long pregId, Long prdId, String prdNombre, String clienteNombre) {}
