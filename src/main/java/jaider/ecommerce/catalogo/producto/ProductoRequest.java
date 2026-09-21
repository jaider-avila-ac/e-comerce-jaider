package jaider.ecommerce.catalogo.producto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ProductoRequest(
        Long catId,
        Long subId,
        String nombre,
        String slug,
        String descripcion,
        Long precio,                    // COP pesos — precio que paga el cliente ahora
        Long precioAntes,               // COP pesos — precio tachado original (solo con oferta)
        OffsetDateTime ofertaHasta,     // fecha de vencimiento de la oferta (opcional)
        Map<String, Object> fichaTecnica,
        Boolean activo,
        List<VarianteRequest> variantes,
        List<ImagenRequest> imagenes,
        // Referencia opcional a un empaque (caja) ya creado por la tienda — PLAN_INTEGRACION_
        // ENVIA.md, Fase 1.
        Long empaqueId,
        // subId/empaqueId son opcionales por diseño (un producto puede no tener subcategoría ni
        // empaque) — pero en una ACTUALIZACIÓN PARCIAL, "no venir en el request" y "venir en
        // null a propósito" tienen que distinguirse, o cualquier PUT que no toque estos dos
        // campos los borra sin querer (pasó de verdad: una actualización que solo tocaba
        // ficha_tecnica le borró la subcategoría a un producto). Por eso limpiarSubId/
        // limpiarEmpaqueId son la única forma de poner subId/empaqueId en null — si vienen
        // null pero sin el flag correspondiente en true, el valor actual se deja intacto. El
        // panel (buildBasePayload) siempre manda estos flags explícitamente en cada guardado,
        // así que el botón "Sin subcategoría"/"Sin empaque asignado" sigue funcionando igual.
        Boolean limpiarSubId,
        Boolean limpiarEmpaqueId
) {}
