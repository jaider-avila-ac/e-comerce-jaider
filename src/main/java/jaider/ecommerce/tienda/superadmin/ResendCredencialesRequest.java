package jaider.ecommerce.tienda.superadmin;

import jakarta.validation.constraints.NotBlank;

public record ResendCredencialesRequest(
        @NotBlank String apiKey,
        @NotBlank String from
) {}
