package jaider.ecommerce.catalogo.subcategoria;

public record SubcategoriaRequest(
        Long catId,
        String nombre,
        String slug,
        Short orden,
        Boolean activo
) {}
