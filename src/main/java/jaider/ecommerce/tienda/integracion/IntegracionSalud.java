package jaider.ecommerce.tienda.integracion;

/**
 * Resultado de probar UNA integración (Cloudinary/Resend/Wompi) de una tienda — nunca incluye
 * la llave real, solo si funcionó y, si no, un mensaje seguro de loguear (§14 del plan).
 */
public record IntegracionSalud(String proveedor, boolean ok, String mensaje) {
}
