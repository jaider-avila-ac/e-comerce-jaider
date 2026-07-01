package jaider.ecommerce.usuario.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TiendaResetPasswordRequest(
        @NotBlank String code,
        @NotBlank @Size(min = 6) String newPassword
) {}
