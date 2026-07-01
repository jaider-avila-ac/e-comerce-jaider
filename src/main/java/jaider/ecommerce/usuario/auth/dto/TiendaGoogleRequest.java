package jaider.ecommerce.usuario.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TiendaGoogleRequest(@NotBlank String idToken) {}
