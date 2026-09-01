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
        String webhookWompiUrl,
        // A diferencia de Wompi, esta URL es DISTINTA por tienda (lleva el tnd_id en la ruta) —
        // PLAN_INTEGRACION_ENVIA.md, Fase 5: el formato exacto del payload que manda Envia según
        // el tipo de webhook configurado no está confirmado en documentación (varios formatos
        // posibles, con o sin datos de referencia propia), así que el tenant se resuelve desde
        // la URL en vez de confiar en el contenido — más simple y robusto ante esa incertidumbre.
        String webhookEnviaUrl
) {}
