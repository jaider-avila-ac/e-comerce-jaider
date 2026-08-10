package jaider.ecommerce.pedido;

/** Corrección excepcional de estado (saltar o retroceder) — motivo obligatorio, solo admin/superadmin. */
public record CorregirEstadoRequest(String estado, String motivo) {}