package jaider.ecommerce.sucursal;

/**
 * Edición de una sucursal YA existente — crear una sucursal nueva es exclusivo del superadmin
 * (decisión explícita del usuario, 2026-09-01; ese panel todavía no se construye). El admin de
 * la tienda solo puede editar el día a día de sus sucursales: contacto y dirección de origen
 * para envíos (PLAN_INTEGRACION_ENVIA.md). Todos los campos son opcionales — null significa
 * "no tocar", igual que el resto de los *Request de esta app (TiendaConfigRequest, etc.).
 */
public record SucursalUpdateRequest(
        String nombre,
        String whatsapp,
        Boolean activo,
        String envioOrigenNombre,
        String envioOrigenTelefono,
        String envioOrigenDireccion,
        String envioOrigenComplemento,
        String envioOrigenDepartamento,
        String envioOrigenMunicipio,
        String envioOrigenCodigoPostal
) {}
