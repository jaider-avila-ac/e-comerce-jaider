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
        String imagenUrl    // primera imagen del primer producto activo de la colección
) {}
