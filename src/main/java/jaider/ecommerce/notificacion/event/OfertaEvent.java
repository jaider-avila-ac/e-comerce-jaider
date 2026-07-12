package jaider.ecommerce.notificacion.event;

/** Se publicó/activó una promoción — se notifica a todos los clientes activos de la tienda. */
public record OfertaEvent(Long tndId, String titulo, String cuerpo, String entidadTipo, Long entidadId) {}
