package jaider.ecommerce.tienda.integracion;

/** Credenciales de Resend de UNA tienda — nunca se envían al frontend ni se registran en logs. */
public record ResendCredentials(String apiKey, String from) {
}
