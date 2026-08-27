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
        // Descuentos manuales (venta local, precio negociado en mostrador) sobre pedidos
        // entregados/en camino — hoy solo lo genera VentaLocalService; los pedidos online no lo
        // tocan, así que siempre da 0 para ese canal (ver ped_descuento_centavos).
        Long totalDescuentos,
        // Plata de pedidos "pagado" — ya cobrados de verdad, pero todavía no pasaron a
        // preparando (ese es el corte que cuenta como ingreso, ver totalIngresos). Es dinero
        // real del cliente, pero aún no se cuenta como ingreso confirmado del negocio.
        Long ingresosPendientes,
        Long totalPedidos,
        Long pedidosEsteMes,
        Long pedidosEnProceso,     // pagado + preparando + enviado
        Long ticketPromedio,       // COP — total_ingresos / total_pedidos (0 si sin pedidos)
        Long totalClientes,
        Long clientesEsteMes,
        Long totalProductos,
        Long productosActivos
) {}
