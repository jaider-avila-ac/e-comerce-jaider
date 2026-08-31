package jaider.ecommerce.tienda.integracion;

/** Credenciales de Cloudinary de UNA tienda — nunca se envían al frontend ni se registran en logs. */
public record CloudinaryCredentials(String cloudName, String apiKey, String apiSecret) {

    // Ver el mismo razonamiento en WompiCredentials.toString(): el toString() por defecto de un
    // record imprime todos los campos, así que se redacta a propósito. cloudName no es secreto
    // (aparece en cada URL pública de una imagen), apiKey/apiSecret sí.
    @Override
    public String toString() {
        return "CloudinaryCredentials[cloudName=" + cloudName + ", apiKey=***, apiSecret=***]";
    }
}
