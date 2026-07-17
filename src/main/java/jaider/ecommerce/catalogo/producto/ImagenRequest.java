package jaider.ecommerce.catalogo.producto;

public record ImagenRequest(
        String url,
        Short orden,
        String tipo,   // "imagen" | "video" — null defaults to "imagen"
        // Color de la variante a la que pertenece esta imagen (nullable = "todos los colores").
        // Se resuelve a var_id en el backend (ver ProductoService.saveImagenes) — nunca se recibe
        // el var_id directo, porque al crear un producto nuevo las variantes recién elegidas
        // todavía no tienen id en el momento en que el admin arma esta lista en el frontend.
        String color
) {}
