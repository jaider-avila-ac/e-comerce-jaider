package jaider.ecommerce.notificacion.event;

/** El staff respondió la pregunta de un cliente — se le avisa a quien preguntó. */
public record PreguntaRespondidaEvent(Long tndId, Long usrId, Long pregId, Long prdId, String prdNombre) {}
