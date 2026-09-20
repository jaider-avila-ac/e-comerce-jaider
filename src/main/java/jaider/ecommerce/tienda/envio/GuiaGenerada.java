package jaider.ecommerce.tienda.envio;

/** Resultado de generar una guía REAL con Envia (POST /ship/generate/) — a diferencia de
 *  {@link CotizacionCarrier}, esto representa un envío de verdad ya creado y cobrado. */
public record GuiaGenerada(
        String carrier, String servicio, String shipmentId,
        String trackingNumber, String trackUrl, String labelUrl, long totalPriceCop
) {}
