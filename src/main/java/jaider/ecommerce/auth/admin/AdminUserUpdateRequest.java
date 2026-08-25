package jaider.ecommerce.auth.admin;

/** Edición de perfil de un colaborador ya creado — no cambia rol.
 *  password: opcional — null/blank = no se toca; si viene, el admin la está reseteando
 *  (el colaborador no tiene "contraseña actual" que confirmar, a diferencia del cambio de
 *  contraseña autenticado del cliente en la tienda).
 *  usuario: opcional — null/blank = no se toca el email; si viene, es solo la parte antes de
 *  "@" (mismo campo que en la creación — ver AdminUserRequest.usuario), el dominio de la tienda
 *  se mantiene fijo. */
public record AdminUserUpdateRequest(
        String nombre, Long sucursalId, String apellido, String telefono, String cargo,
        String tipoDocumento, String numeroDocumento, String fechaNacimiento, String password,
        String usuario
) {
}
