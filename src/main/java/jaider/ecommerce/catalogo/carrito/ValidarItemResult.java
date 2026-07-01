package jaider.ecommerce.catalogo.carrito;

public record ValidarItemResult(
        Long productId,
        String talla,
        String color,
        int stockDisponible,
        boolean productoActivo
) {}
