package jaider.ecommerce.tienda.envio;

/**
 * Una cotización real ya resuelta de un carrier específico.
 *
 * @param servicioCodigo el código real que Envia necesita en {@code shipment.service} para
 *                       generar la guía (ej. "premier") — NUNCA la descripción legible.
 * @param servicioDescripcion nombre legible para mostrarle al admin/cliente (ej. "ServiEntrega
 *                       Premier") — nunca se manda de vuelta a Envia.
 */
public record CotizacionCarrier(String carrier, String servicioCodigo, String servicioDescripcion,
                                 long precioCop, String tiempoEstimado) {}
