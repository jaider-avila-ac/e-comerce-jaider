package jaider.ecommerce.tienda.envio;

import java.util.List;

/** Datos de la pantalla "preparar envío" del admin — PLAN_INTEGRACION_ENVIA.md, Fase 4: el
 *  paquete ya calculado (peso/caja) y las cotizaciones reales de TODAS las transportadoras
 *  configuradas que respondieron (a diferencia de {@link EnvioCotizacionService}, que solo
 *  devuelve la primera — acá el admin necesita comparar antes de elegir con cuál generar la
 *  guía real). */
public record PrepararEnvioResponse(
        List<PaqueteCalculado> paquetes,
        List<CotizacionCarrier> cotizaciones,
        boolean guiaYaGenerada,
        String transportadoraActual,
        String codigoRastreoActual,
        String guiaUrlActual,
        // Corrección de auditoría (2026-09-01): faltaban acá — sin esto, el costo real y el
        // shipmentId de una guía ya generada solo se veían una vez, en la respuesta inmediata de
        // generar-guia, y desaparecían al recargar la pantalla.
        String shipmentIdActual,
        Long costoRealCentavosActual
) {}
