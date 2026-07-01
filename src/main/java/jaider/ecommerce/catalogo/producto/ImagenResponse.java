package jaider.ecommerce.catalogo.producto;

public record ImagenResponse(
        Long id,
        String url,
        Short orden,
        String tipo,   // "imagen" | "video"
        Long varId     // var_id de la variante de color asociada (nullable)
) {}
