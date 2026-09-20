package jaider.ecommerce.auth.superadmin;

/**
 * Totales agregados de TODA la plataforma, nunca desglosados por tienda ni con detalle
 * operativo (productos, categorías, pedidos individuales, etc.) — es explícitamente lo único
 * que un superadmin puede ver (decisión del usuario, 2026-08-30): "solamente podrá ver totales".
 */
public record SuperadminResumenResponse(
        long tiendasActivas,
        long totalClientes
) {}
