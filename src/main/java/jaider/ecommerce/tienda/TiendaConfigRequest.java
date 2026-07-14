package jaider.ecommerce.tienda;

public record TiendaConfigRequest(
        Boolean envioGratisActivo,
        Long envioGratisDesde, // pesos COP
        Long envioCosto,       // pesos COP — costo de envío estándar cuando no aplica envío gratis
        String dominioStaff    // ej. "calzacaribe.com" — usado para armar el email de colaboradores
) {}
