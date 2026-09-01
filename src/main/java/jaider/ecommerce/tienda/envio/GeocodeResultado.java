package jaider.ecommerce.tienda.envio;

/**
 * Lo que la Geocodes API de Envia.com (geocodes.envia.com, sin auth, un solo ambiente — no hay
 * sandbox separado) resuelve a partir de un código postal colombiano — PLAN_INTEGRACION_ENVIA.md,
 * Fase 3, verificado en vivo con la documentación real que compartió el usuario.
 *
 * @param locality  nombre de ciudad (ej. "Santa Marta") — sirve como {@code city} para la
 *                  mayoría de transportadoras.
 * @param state3    código de 3 letras del departamento (ej. "MAG", "DC") — es el {@code state}
 *                  que la API de cotización realmente acepta, NUNCA el nombre completo.
 * @param stat8Digit código DANE de 8 dígitos del municipio (ej. "47001000") — Servientrega
 *                  específicamente lo exige como {@code city} en vez del nombre (verificado en
 *                  vivo: con el nombre normal responde "No se ha encontrado el Codigo DANE").
 */
public record GeocodeResultado(String locality, String state3, String stat8Digit) {}
