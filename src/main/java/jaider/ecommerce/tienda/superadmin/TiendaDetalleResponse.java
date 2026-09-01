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
        List<CampoEstadoResponse> cloudinary,
        List<CampoEstadoResponse> envia,
        // Misma URL para TODAS las tiendas — Wompi identifica a cuál pertenece cada evento por
        // la referencia del pago (ver PagoWebhookService), no por esta URL. Se incluye igual acá
        // para que el panel tenga un botón "copiar" sin que el operador tenga que saberla de
        // memoria al configurar el merchant en Wompi.
        String webhookWompiUrl
) {}
