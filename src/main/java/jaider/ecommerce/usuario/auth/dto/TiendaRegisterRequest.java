package jaider.ecommerce.usuario.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TiendaRegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6) String password,
        @NotBlank String nombre,
        String apellido,
        String tipoDocumento,   // opcional — si coincide con un cliente de venta local ya
        String numeroDocumento  // creado en esta tienda, se reutiliza esa misma cuenta
) {}
