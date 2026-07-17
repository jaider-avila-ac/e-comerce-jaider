package jaider.ecommerce.catalogo.coleccion;

import java.util.List;

public record ColeccionResponse(
        Long id,
        String nombre,
        String slug,
        String descripcion,
        boolean activo,
        int orden,
        List<Long> productoIds,
        String imagenUrl    // imagen de portada/banner de la colección (col_imagen_url)
) {}
