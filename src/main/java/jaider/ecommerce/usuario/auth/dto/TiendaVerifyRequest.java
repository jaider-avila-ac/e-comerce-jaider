package jaider.ecommerce.usuario.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record TiendaVerifyRequest(
        @NotBlank @Email String email,
        @NotBlank String code
) {}
