package jaider.ecommerce.auth.admin;

public record AdminUserResponse(
        Long id, String email, String nombre, String rol, boolean activo,
        String apellido, String telefono, String cargo,
        String tipoDocumento, String numeroDocumento, String fechaNacimiento
) {
}
