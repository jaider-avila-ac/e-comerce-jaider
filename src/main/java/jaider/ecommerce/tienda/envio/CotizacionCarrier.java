package jaider.ecommerce.tienda.envio;

/** Una cotización real ya resuelta de un carrier específico. */
public record CotizacionCarrier(String carrier, String servicio, long precioCop, String tiempoEstimado) {}
