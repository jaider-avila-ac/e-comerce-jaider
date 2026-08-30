package jaider.ecommerce.tienda.aprovisionamiento;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Datos de entrada para dar de alta una tienda nueva (§15 del plan). Los secretos
 * (WOMPI_&lt;secretAlias&gt;_*, RESEND_&lt;secretAlias&gt;_*, CLOUDINARY_&lt;secretAlias&gt;_*)
 * NO se piden acá — deben existir ya en las variables de entorno del proceso (el operador los
 * agrega y reinicia el backend ANTES de llamar a este endpoint) — este proceso solo verifica
 * que estén, nunca los recibe ni los guarda.
 */
public record TenantProvisioningRequest(
        @NotBlank String nombreComercial,
        @NotBlank String razonSocial,
        @NotBlank String nit,
        @NotBlank @Size(max = 60) String slug,
        @NotBlank String dominioPrincipal,
        @NotBlank @Email String emailContacto,
        String emailNotificacionPedidos,
        String whatsapp,
        @NotBlank String secretAlias,   // debe cumplir ^[A-Z0-9_]+$ (ver constraint en BD)
        @NotBlank @Email String adminEmail,
        @NotBlank @Size(min = 8) String adminPassword,
        @NotBlank String adminNombre,
        String envioModo,                    // "contra_entrega" (por defecto) | "fijo"
        Long envioCostoCentavos,
        Boolean envioGratisActivo,
        Long envioGratisDesdeCentavos
) {}
