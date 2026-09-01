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
        // Referencia opcional a una especificación logística ya creada por la tienda (peso +
        // dimensiones reutilizables) — PLAN_INTEGRACION_ENVIA.md, Fase 1. Mismo criterio que
        // subId: siempre se aplica tal cual venga, así que mandar null la quita explícitamente.
        Long especificacionId
) {}
