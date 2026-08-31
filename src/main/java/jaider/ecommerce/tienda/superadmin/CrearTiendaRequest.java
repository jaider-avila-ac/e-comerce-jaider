package jaider.ecommerce.tienda.superadmin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Datos para crear una tienda en estado BORRADOR desde el panel de superadmin — igual que
 * {@link jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningRequest} pero SIN pedir un
 * alias de secretos a mano (se deriva del slug automáticamente, ver
 * {@link SuperadminTiendaService}) porque en este flujo las credenciales van cifradas en la BD,
 * no en variables de entorno que necesiten un nombre elegido de antemano.
 */
public record CrearTiendaRequest(
        @NotBlank String nombreComercial,
        @NotBlank String razonSocial,
        @NotBlank String nit,
        @NotBlank @Size(max = 60) String slug,
        @NotBlank String dominioPrincipal,
        @NotBlank @Email String emailContacto,
        String emailNotificacionPedidos,
        String whatsapp,
        @NotBlank @Email String adminEmail,
        @NotBlank @Size(min = 8) String adminPassword,
        @NotBlank String adminNombre,
        String envioModo,
        Long envioCostoCentavos,
        Boolean envioGratisActivo,
        Long envioGratisDesdeCentavos
) {}
