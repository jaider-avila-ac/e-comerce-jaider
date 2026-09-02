package jaider.ecommerce.tienda.envio;

/** Envia indicó generación, pero la respuesta no permite registrar la guía de forma segura. */
public class EnviaGuiaResultadoInciertoException extends RuntimeException {
    public EnviaGuiaResultadoInciertoException(String message) {
        super(message);
    }
}
