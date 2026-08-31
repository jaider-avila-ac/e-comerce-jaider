package jaider.ecommerce.tienda.integracion;

/** Credenciales de Wompi de UNA tienda — nunca se envían al frontend ni se registran en logs. */
public record WompiCredentials(String publicKey, String privateKey, String integrityKey, String eventsKey) {

    // Defensa en profundidad (§6.3 del plan: "nunca registrar los valores"): un record de Java
    // genera un toString() por defecto que imprime TODOS los campos — si algún día un log.debug()
    // o una excepción loguea este objeto entero por error, esto evita que la llave real llegue a
    // un log o a la consola. La llave pública NO es secreta (Wompi la expone en el checkout del
    // navegador), así que sí se muestra completa; el resto queda enmascarado.
    @Override
    public String toString() {
        return "WompiCredentials[publicKey=" + publicKey + ", privateKey=***, integrityKey=***, eventsKey=***]";
    }
}
