package jaider.ecommerce.tienda.integracion;

/** Credenciales de Resend de UNA tienda — nunca se envían al frontend ni se registran en logs. */
public record ResendCredentials(String apiKey, String from) {

    // Ver el mismo razonamiento en WompiCredentials.toString(). "from" no es secreto (es el
    // remitente visible en cada correo enviado), apiKey sí.
    @Override
    public String toString() {
        return "ResendCredentials[apiKey=***, from=" + from + "]";
    }
}
