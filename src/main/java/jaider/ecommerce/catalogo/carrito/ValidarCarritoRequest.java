package jaider.ecommerce.catalogo.carrito;

import java.util.List;

public record ValidarCarritoRequest(List<Item> items) {
    public record Item(Long productId, String talla, String color, int cantidad) {}
}
