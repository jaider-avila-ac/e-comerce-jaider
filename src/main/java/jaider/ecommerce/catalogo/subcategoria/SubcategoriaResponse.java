package jaider.ecommerce.catalogo.subcategoria;

public record SubcategoriaResponse(
        Long id,
        Long catId,
        String nombre,
        String slug,
        Short orden,
        Boolean activo
) {}
