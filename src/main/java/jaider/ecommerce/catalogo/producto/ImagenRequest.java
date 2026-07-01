package jaider.ecommerce.catalogo.producto;

public record ImagenRequest(
        String url,
        Short orden,
        String tipo,   // "imagen" | "video" — null defaults to "imagen"
        Long varId     // var_id de la variante de color (nullable)
) {}
