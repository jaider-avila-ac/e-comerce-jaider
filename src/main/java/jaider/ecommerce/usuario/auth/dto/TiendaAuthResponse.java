package jaider.ecommerce.usuario.auth.dto;

public record TiendaAuthResponse(
        String token,
        Long userId,
        String email,
        String nombre,
        String apellido,
        String avatar,
        String provider
) {}
