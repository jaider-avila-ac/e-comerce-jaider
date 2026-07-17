package jaider.ecommerce.catalogo.producto;

public record ImagenResponse(
        Long id,
        String url,
        Short orden,
        String tipo,   // "imagen" | "video"
        String color   // color de la variante asociada (nullable = "todos los colores")
) {}
