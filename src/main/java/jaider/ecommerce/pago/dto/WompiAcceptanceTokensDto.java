package jaider.ecommerce.pago.dto;

/**
 * Tokens de aceptación obtenidos de GET /merchants/{publicKey}.
 * El frontend los necesita para mostrar los términos y firmar la creación de la fuente de pago.
 */
public record WompiAcceptanceTokensDto(
        String wompiBaseUrl,
        String publicKey,
        String acceptanceToken,
        String acceptancePermalink,
        String personalAuthToken,
        String personalAuthPermalink
) {
}
