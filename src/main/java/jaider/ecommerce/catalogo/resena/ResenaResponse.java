package jaider.ecommerce.catalogo.resena;

import java.time.OffsetDateTime;

public record ResenaResponse(
        Long id,
        Integer calificacion,
        String titulo,
        String cuerpo,
        String autor,
        OffsetDateTime creadoEn
) {
}
