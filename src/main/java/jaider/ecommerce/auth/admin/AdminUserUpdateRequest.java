package jaider.ecommerce.auth.admin;

/** Edición de perfil de un colaborador ya creado — no cambia email/password/rol. */
public record AdminUserUpdateRequest(
        String nombre, String apellido, String telefono, String cargo,
        String tipoDocumento, String numeroDocumento, String fechaNacimiento
) {
}
