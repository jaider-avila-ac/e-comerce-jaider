package jaider.ecommerce.reporte;

public record ReporteResumenResponse(
        Long totalIngresos,        // COP — solo pedidos entregados
        Long ingresosEsteMes,      // COP — pedidos entregados en el mes actual
        Long totalPedidos,
        Long pedidosEsteMes,
        Long pedidosEnProceso,     // pagado + preparando + enviado
        Long ticketPromedio,       // COP — total_ingresos / total_pedidos (0 si sin pedidos)
        Long totalClientes,
        Long clientesEsteMes,
        Long totalProductos,
        Long productosActivos
) {}
