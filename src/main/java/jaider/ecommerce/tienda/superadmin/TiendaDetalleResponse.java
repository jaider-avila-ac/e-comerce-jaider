package jaider.ecommerce.tienda.superadmin;

import java.util.List;

public record TiendaDetalleResponse(
        Long id,
        String nombre,
        String slug,
        boolean activo,
        String dominioPrincipal,
        List<CampoEstadoResponse> wompi,
        List<CampoEstadoResponse> resend,
        List<CampoEstadoResponse> cloudinary
) {}
