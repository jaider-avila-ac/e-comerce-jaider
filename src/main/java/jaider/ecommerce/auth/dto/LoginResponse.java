package jaider.ecommerce.auth.dto;

public record LoginResponse(
        String token,
        long expiresIn,
        String email,
        String nombre,
        Long tndId,
        String rol
) {}
