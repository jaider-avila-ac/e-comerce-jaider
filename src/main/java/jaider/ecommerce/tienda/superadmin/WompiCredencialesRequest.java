package jaider.ecommerce.tienda.superadmin;

import jakarta.validation.constraints.NotBlank;

/** privateKey es opcional a propósito — ver WompiCredentials/TenantIntegrationResolver. */
public record WompiCredencialesRequest(
        @NotBlank String publicKey,
        String privateKey,
        @NotBlank String integrityKey,
        @NotBlank String eventsKey
) {}
