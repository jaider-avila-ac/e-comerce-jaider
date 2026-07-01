package jaider.ecommerce.catalogo.producto;

public record VarianteResponse(
        Long id,
        String talla,
        String color,
        String colorHex,
        Integer stock,
        Boolean activo,
        String estadoStock   // "agotado" | "bajo" | "disponible" — igual que v_inventario_simple
) {}
