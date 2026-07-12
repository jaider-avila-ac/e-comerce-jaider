package jaider.ecommerce.catalogo.carrito;

public record CarritoItemRequest(
        Long prdId,
        String talla,
        String color,
        Integer cantidad
) {
}
