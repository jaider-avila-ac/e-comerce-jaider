package jaider.ecommerce.usuario.evento;

import jakarta.validation.constraints.NotBlank;

public record EventoRequest(
        @NotBlank String tipo,
        String entidadTipo,
        Long entidadId
) {}
