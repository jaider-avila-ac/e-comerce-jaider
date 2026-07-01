package jaider.ecommerce.catalogo.publico;

import java.util.List;

public record PublicCategoriaResponse(
        Long id,
        String nombre,
        String slug,
        String imagenUrl,
        List<String> subcategorias
) {}
