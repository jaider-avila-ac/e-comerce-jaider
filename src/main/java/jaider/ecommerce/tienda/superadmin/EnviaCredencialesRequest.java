package jaider.ecommerce.tienda.superadmin;

import jakarta.validation.constraints.NotBlank;

/** El ambiente (sandbox/producción) NO se guarda acá — es tnd_envia_ambiente en /tienda/config,
 *  no es un secreto. Este endpoint guarda el token cifrado y, opcionalmente (Fase 5), el
 *  secreto para verificar los webhooks de seguimiento — ver EnviaCredentials.webhookSecret. */
public record EnviaCredencialesRequest(
        @NotBlank String apiToken,
        String webhookSecret
) {}
