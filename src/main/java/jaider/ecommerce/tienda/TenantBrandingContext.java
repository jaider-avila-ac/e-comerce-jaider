package jaider.ecommerce.tienda;

/**
 * Datos de identidad de UNA tienda para personalizar correos y demás superficies orientadas al
 * cliente (§8.3 del plan) — ninguna plantilla debe contener literalmente "Calzacaribe" o
 * cualquier otro nombre de comercio fijo en el código.
 *
 * Campos opcionales (razonSocial, nit, emailSoporte, colorPrincipal) pueden venir null si la
 * tienda todavía no los cargó desde el panel — quien arma el HTML debe omitir esa parte, nunca
 * mostrar un placeholder vacío o "null".
 */
public record TenantBrandingContext(
        Long tenantId,
        String nombreComercial,
        String logoUrl,
        String sitioWeb,
        String emailSoporte,
        String whatsapp,
        String colorPrincipal,
        String razonSocial,
        String nit
) {
    private static final String COLOR_POR_DEFECTO = "#111111";

    /** Nunca null — si la tienda no configuró un color, usa el gris oscuro neutral que ya
     *  usaban las plantillas antes de personalizarse por tenant. */
    public String colorPrincipalODefecto() {
        return (colorPrincipal != null && !colorPrincipal.isBlank()) ? colorPrincipal : COLOR_POR_DEFECTO;
    }
}
