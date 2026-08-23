package jaider.ecommerce.catalogo.pregunta;

import java.time.OffsetDateTime;

/** Vista del panel admin — a diferencia de la pública, sí incluye eliminadas (con quién y
 *  cuándo) y la identidad de quién respondió, para que el staff pueda auditar el módulo. */
public record PreguntaAdminResponse(
        Long id,
        Long prdId,
        String prdNombre,       // null si el producto ya se borró (la pregunta también se borra
                                 // en cascada en ese caso — ver Pregunta — así que esto no debería
                                 // pasar en la práctica, pero se deja por si acaso)
        String clienteNombre,
        String clienteEmail,
        String texto,
        boolean editada,
        boolean eliminada,
        String eliminadaPor,
        OffsetDateTime eliminadaEn,
        String respuestaTexto,
        Long respuestaAdminId,
        String respuestaAdminNombre,
        boolean respuestaEditada,
        OffsetDateTime respondidaEn,
        OffsetDateTime creadoEn
) {}
