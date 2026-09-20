package jaider.ecommerce.tienda.envio;

/** El admin elige, de las cotizaciones que le mostró "preparar envío", con cuál transportadora
 *  y servicio generar la guía real. */
public record GenerarGuiaRequest(String carrier, String servicio) {}
