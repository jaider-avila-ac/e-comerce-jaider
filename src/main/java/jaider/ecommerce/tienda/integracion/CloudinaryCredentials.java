package jaider.ecommerce.tienda.integracion;

/** Credenciales de Cloudinary de UNA tienda — nunca se envían al frontend ni se registran en logs. */
public record CloudinaryCredentials(String cloudName, String apiKey, String apiSecret) {
}
