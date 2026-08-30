package jaider.ecommerce.tienda.integracion;

/** Credenciales de Wompi de UNA tienda — nunca se envían al frontend ni se registran en logs. */
public record WompiCredentials(String publicKey, String privateKey, String integrityKey, String eventsKey) {
}
