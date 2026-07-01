package jaider.ecommerce.catalogo.categoria;

public record CategoriaRequest(
        String nombre,
        String slug,
        String imagenUrl,
        Short orden,
        Boolean activo
) {}
