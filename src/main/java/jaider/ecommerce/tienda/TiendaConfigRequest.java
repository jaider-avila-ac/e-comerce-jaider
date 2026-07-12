package jaider.ecommerce.tienda;

public record TiendaConfigRequest(
        Boolean envioGratisActivo,
        Long envioGratisDesde, // pesos COP
        String dominioStaff    // ej. "calzacaribe.com" — usado para armar el email de colaboradores
) {}
