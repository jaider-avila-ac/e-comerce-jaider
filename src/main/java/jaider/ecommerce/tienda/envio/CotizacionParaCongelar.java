package jaider.ecommerce.tienda.envio;

import java.util.List;

/**
 * Todo lo que hay que congelar en el pedido en el momento del checkout — corrección de
 * auditoría (2026-09-01): antes de esto, ni los paquetes (peso/dimensiones) ni la transportadora/
 * servicio cotizados quedaban guardados en ningún lado, así que generar la guía real más tarde
 * podía usar datos distintos a los que el cliente vio. Ver {@code Pedido.envioCotizacionSnapshot}.
 */
public record CotizacionParaCongelar(EnvioCotizacionResponse respuesta, List<PaqueteCalculado> paquetes) {}
