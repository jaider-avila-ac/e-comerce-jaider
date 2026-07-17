package jaider.ecommerce.catalogo.coleccion;

import java.util.List;

public record ColeccionRequest(
        String nombre,
        String slug,
        String descripcion,
        Boolean activo,
        Integer orden,
        List<Long> productoIds, // Jackson SNAKE_CASE → deserializa "producto_ids"
        String imagenUrl        // imagen de portada/banner, subida vía /api/v1/upload/coleccion
) {}
