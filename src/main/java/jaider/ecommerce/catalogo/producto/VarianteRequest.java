package jaider.ecommerce.catalogo.producto;

public record VarianteRequest(
        Long id,
        String talla,
        String color,
        String colorHex,
        Integer stock,
        Boolean activo
) {}
