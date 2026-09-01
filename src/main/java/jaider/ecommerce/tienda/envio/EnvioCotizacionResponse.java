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
        // Código real de servicio (ej. "ground") — no se muestra al cliente, pero se necesita
        // para congelar EXACTAMENTE qué se cotizó (ver CotizacionParaCongelar / auditoría 2026-09-01).
        String servicioCodigo,
        String tiempoEstimado,
        boolean estimado
) {}
