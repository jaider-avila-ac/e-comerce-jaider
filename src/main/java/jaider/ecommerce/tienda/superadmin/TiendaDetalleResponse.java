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
        // El dominio de esta URL es el PROPIO de cada tienda (no uno compartido) — Wompi igual
        // identifica a cuál pertenece cada evento por la referencia del pago (ver
        // PagoWebhookService), no por esta URL, así que cualquier dominio de la tienda sirve;
        // se usa el propio para que lo que el comerciante pegue en su cuenta de Wompi diga su
        // propio dominio, no el de otra tienda.
        String webhookWompiUrl,
        // A diferencia de Wompi, esta URL es DISTINTA por tienda (lleva el tnd_id en la ruta) —
        // PLAN_INTEGRACION_ENVIA.md, Fase 5: el formato exacto del payload que manda Envia según
        // el tipo de webhook configurado no está confirmado en documentación (varios formatos
        // posibles, con o sin datos de referencia propia), así que el tenant se resuelve desde
        // la URL en vez de confiar en el contenido — más simple y robusto ante esa incertidumbre.
        String webhookEnviaUrl,
        // Solo si YA tiene api key generada (ver TiendaApiKeyService) — nunca el valor, eso
        // existe en texto plano una única vez, justo al generarla.
        boolean apiKeyConfigurada
) {}
