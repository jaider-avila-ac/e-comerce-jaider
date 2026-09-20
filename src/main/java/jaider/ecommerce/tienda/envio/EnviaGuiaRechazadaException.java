package jaider.ecommerce.tienda.envio;

/** Respuesta explícita de Envia que confirma que no se generó una guía. */
public class EnviaGuiaRechazadaException extends RuntimeException {
    public EnviaGuiaRechazadaException(String message) {
        super(message);
    }
}
