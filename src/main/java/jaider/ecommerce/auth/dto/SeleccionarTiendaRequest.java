package jaider.ecommerce.auth.dto;

import jakarta.validation.constraints.NotNull;

public record SeleccionarTiendaRequest(
        @NotNull Long tenantId
) {}
