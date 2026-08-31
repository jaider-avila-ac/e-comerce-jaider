package jaider.ecommerce.tienda.superadmin;

public record TiendaResumenResponse(
        Long id,
        String nombre,
        String slug,
        boolean activo,
        String dominioPrincipal
) {}
