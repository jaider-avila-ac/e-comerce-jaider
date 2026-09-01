package jaider.ecommerce.tienda.envio;

/**
 * Precio de envío para mostrarle al cliente al armar el carrito — PLAN_INTEGRACION_ENVIA.md,
 * Fase 3. {@code estimado=true} significa que NINGÚN carrier real respondió y se usó el costo
 * fijo de la tienda como respaldo — el cliente sí o sí ve un precio, nunca un error.
 */
public record EnvioCotizacionResponse(
        long precioCentavos,
        String transportadora,
        String servicio,
        String tiempoEstimado,
        boolean estimado
) {}
