package jaider.ecommerce.pedido;

/** mostrar: "codigo" | "link" | "ambos" — qué le muestra la tienda al cliente. */
public record SeguimientoRequest(String transportadora, String codigoRastreo, String link, String mostrar) {}
