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
        // Todo lo descontado sobre pedidos entregados/en camino, sin importar el origen: venta
        // local (precio negociado en mostrador, ver VentaLocalService) o cupón online (ver
        // CuponService) — ambos escriben en ped_descuento_centavos. totalDescuentosCupon de abajo
        // es el subconjunto de este total que específicamente viene de un cupón, para no mezclar
        // ambos orígenes en una sola cifra sin poder distinguirlos.
        Long totalDescuentos,
        Long totalDescuentosCupon,
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
