package jaider.ecommerce.tienda.integracion;

/** Credenciales de Envia.com de UNA tienda — nunca se envían al frontend ni se registran en logs. */
public record EnviaCredentials(String apiToken) {

    // Ver el mismo razonamiento en WompiCredentials.toString(): el toString() por defecto de un
    // record imprime todos los campos, así que se redacta a propósito.
    @Override
    public String toString() {
        return "EnviaCredentials[apiToken=***]";
    }
}
