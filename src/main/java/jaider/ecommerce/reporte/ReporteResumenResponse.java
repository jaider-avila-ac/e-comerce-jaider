package jaider.ecommerce.reporte;

public record ReporteResumenResponse(
        Long totalIngresos,        // COP — solo pedidos entregados
        Long ingresosEsteMes,      // COP — pedidos entregados en el mes actual
        // Desglose de totalIngresos: cuánto es venta real de producto vs. envío cobrado al
        // cliente (que en modo "fijo" se cobra junto con el pedido pero no es venta de mercancía
        // — ver Tienda.envioModo). En modo "contra entrega" ingresosEnvio siempre da 0, porque
        // ese envío nunca pasa por la tienda. Mismo criterio de redacción por rol que totalIngresos.
        Long ingresosProductos,
        Long ingresosEnvio,
        Long totalPedidos,
        Long pedidosEsteMes,
        Long pedidosEnProceso,     // pagado + preparando + enviado
        Long ticketPromedio,       // COP — total_ingresos / total_pedidos (0 si sin pedidos)
        Long totalClientes,
        Long clientesEsteMes,
        Long totalProductos,
        Long productosActivos
) {}
