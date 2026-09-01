package jaider.ecommerce.tienda.integracion;

/**
 * Credenciales de Envia.com de UNA tienda — nunca se envían al frontend ni se registran en logs.
 *
 * @param webhookSecret PLAN_INTEGRACION_ENVIA.md, Fase 5 — opcional (igual que
 *                      WompiCredentials.privateKey): sirve para verificar que un webhook de
 *                      seguimiento realmente viene de Envia (Bearer token que el admin configura
 *                      al registrar el webhook en el panel de Envia — ver
 *                      docs.envia.com/reference/webhooks, "v1 solo soporta Bearer token
 *                      opcional"). Sin esto configurado, EnvioWebhookService igual procesa el
 *                      evento (mismo criterio que Wompi con integraciones a medio configurar),
 *                      pero sin verificar su autenticidad — no bloquea, solo es menos seguro.
 */
public record EnviaCredentials(String apiToken, String webhookSecret) {

    // Ver el mismo razonamiento en WompiCredentials.toString(): el toString() por defecto de un
    // record imprime todos los campos, así que se redacta a propósito.
    @Override
    public String toString() {
        return "EnviaCredentials[apiToken=***, webhookSecret=***]";
    }
}
