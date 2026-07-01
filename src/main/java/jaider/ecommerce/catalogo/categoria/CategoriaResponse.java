package jaider.ecommerce.catalogo.categoria;

import java.time.OffsetDateTime;

public record CategoriaResponse(
        Long id,
        String nombre,
        String slug,
        String imagenUrl,
        Short orden,
        Boolean activo,
        OffsetDateTime creadoEn
) {}
