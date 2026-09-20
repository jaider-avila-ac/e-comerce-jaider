package jaider.ecommerce.tienda.superadmin;

import jakarta.validation.constraints.NotBlank;

public record CloudinaryCredencialesRequest(
        @NotBlank String cloudName,
        @NotBlank String apiKey,
        @NotBlank String apiSecret
) {}
