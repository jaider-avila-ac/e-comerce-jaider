package jaider.ecommerce.auth.dto;

public record AdminMeResponse(
        Long id,
        String email,
        String nombre,
        String rol,
        boolean activo
) {}
