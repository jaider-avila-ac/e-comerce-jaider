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
        String guiaUrlActual
) {}
