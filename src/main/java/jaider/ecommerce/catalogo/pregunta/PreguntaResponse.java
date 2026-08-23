package jaider.ecommerce.catalogo.pregunta;

import java.time.OffsetDateTime;

/** Vista pública (tienda) — nunca incluye eliminadas ni datos de otros clientes. */
public record PreguntaResponse(
        Long id,
        String texto,
        boolean editada,
        String autorNombre,
        boolean esMia,          // para que la tienda muestre editar/borrar solo en la propia
        String respuestaTexto,  // null = todavía sin responder
        OffsetDateTime respondidaEn,
        OffsetDateTime creadoEn
) {}
