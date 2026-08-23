package jaider.ecommerce.reporte;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReporteService {

    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    // ─── Resumen general ──────────────────────────────────────────────────────

    // Una venta cuenta como ingreso desde "preparando" en adelante — no desde "pagado", porque
    // un pedido recién pagado puede tener una alerta de stock sin resolver (ver alertaStock en
    // Pedido) y todavía no es un ingreso confirmado; y no en "entregado" solamente, porque esa
    // transición ahora también la puede disparar el cliente al confirmar recibido, y esa acción
    // no debe tener ningún efecto en las cuentas. Cancelado/devuelto quedan fuera del conteo.
    @Transactional(readOnly = true)
    public ReporteResumenResponse resumen(String mes, boolean esAdmin, Long colaboradorId, Long sucursalId) {
        tenantSupport.applyTenant(em);

        // Un colaborador SOLO puede ver sus propias cifras — el controller ya le pasa acá su
        // propio id en vez del que haya pedido (ver ReporteController.resolverAdminId). Si por
        // alguna razón no se pudo resolver quién es, nunca se cae a "sin filtrar" (que mostraría
        // la tienda completa) — se responde vacío en vez de arriesgar una fuga de datos.
        if (!esAdmin && colaboradorId == null) {
            return new ReporteResumenResponse(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        Periodo periodo = periodo(mes);
        String pedidosWhere = (periodo.hasRange() ? " WHERE ped_creado_en >= :start AND ped_creado_en < :end " : " WHERE true ")
                + " AND (CAST(:colaboradorId AS BIGINT) IS NULL OR ped_colaborador_id = CAST(:colaboradorId AS BIGINT)) "
                + " AND (CAST(:sucursalId AS BIGINT) IS NULL OR ped_sucursal_id = CAST(:sucursalId AS BIGINT)) ";
        String clientesWhere = periodo.hasRange() ? " WHERE usr_creado_en >= :start AND usr_creado_en < :end " : "";

        Object[] row = (Object[]) em.createNativeQuery("""
            SELECT
              COALESCE(SUM(CASE WHEN ped_estado IN ('preparando', 'enviado', 'entregado')
                                 THEN ped_total_centavos END), 0)                                AS total_ingresos,
              COALESCE(SUM(CASE WHEN ped_estado IN ('preparando', 'enviado', 'entregado')
                                 THEN ped_envio_centavos END), 0)                                AS ingresos_envio,
              COALESCE(SUM(CASE WHEN ped_estado IN ('preparando', 'enviado', 'entregado')
                                 THEN ped_descuento_centavos END), 0)                             AS total_descuentos,
              COUNT(*)                                                                          AS total_pedidos,
              COUNT(*) FILTER (WHERE ped_estado IN ('pagado', 'preparando', 'enviado')) AS en_proceso
            FROM pedidos
            """ + pedidosWhere)
            .unwrap(org.hibernate.query.NativeQuery.class)
            .setProperties(periodo.params())
            .setParameter("colaboradorId", colaboradorId)
            .setParameter("sucursalId", sucursalId)
            .getSingleResult();

        long totalIngresosCentavos = ((Number) row[0]).longValue();
        // "Envío" cobrado dentro de esos ingresos (siempre 0 si el pedido fue "contra entrega" —
        // ver Tienda.envioModo/Pedido.envioContraEntrega); el resto es venta real de producto.
        long ingresosEnvioCentavos = ((Number) row[1]).longValue();
        long ingresosProductosCentavos = totalIngresosCentavos - ingresosEnvioCentavos;
        long totalDescuentosCentavos = ((Number) row[2]).longValue();
        long totalPedidos          = ((Number) row[3]).longValue();
        long pedidosEnProceso      = ((Number) row[4]).longValue();

        Number rowClientes = (Number) em.createNativeQuery("""
            SELECT
              COUNT(*) AS total
            FROM usuarios
            """ + clientesWhere)
            .unwrap(org.hibernate.query.NativeQuery.class)
            .setProperties(periodo.params())
            .getSingleResult();

        long totalClientes  = rowClientes.longValue();

        Object[] rowProd = (Object[]) em.createNativeQuery("""
            SELECT COUNT(*), COUNT(*) FILTER (WHERE prd_activo = true)
            FROM productos
            """).getSingleResult();

        long totalProductos   = ((Number) rowProd[0]).longValue();
        long productosActivos = ((Number) rowProd[1]).longValue();

        long ticketPromedio = totalPedidos > 0 ? (totalIngresosCentavos / 100L) / totalPedidos : 0L;

        // Ya no se redacta el dinero: si llegamos hasta acá, o es admin (ve lo que haya pedido
        // filtrar, incluida la tienda completa) o es un colaborador viendo EXCLUSIVAMENTE lo
        // suyo (colaboradorId ya viene forzado a su propio id) — en ningún caso es dinero ajeno.
        return new ReporteResumenResponse(
                totalIngresosCentavos / 100L,
                totalIngresosCentavos / 100L,
                ingresosProductosCentavos / 100L,
                ingresosEnvioCentavos / 100L,
                totalDescuentosCentavos / 100L,
                totalPedidos,
                totalPedidos,
                pedidosEnProceso,
                ticketPromedio,
                totalClientes,
                totalClientes,
                totalProductos,
                productosActivos
        );
    }

    // ─── Pedidos por estado ───────────────────────────────────────────────────

    // El Dashboard (todo el staff) y Reportes (solo admin) comparten este endpoint — igual que en
    // resumen(), un colaborador solo ve el desglose de SUS propios pedidos (colaboradorId ya
    // viene forzado a su propio id desde el controller cuando no es admin).
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> pedidosPorEstado(String mes, boolean esAdmin, Long colaboradorId, Long sucursalId) {
        tenantSupport.applyTenant(em);
        if (!esAdmin && colaboradorId == null) return List.of();
        Periodo periodo = periodo(mes);
        String where = (periodo.hasRange() ? "WHERE ped_creado_en >= :start AND ped_creado_en < :end " : "WHERE true ")
                + " AND (CAST(:colaboradorId AS BIGINT) IS NULL OR ped_colaborador_id = CAST(:colaboradorId AS BIGINT)) "
                + " AND (CAST(:sucursalId AS BIGINT) IS NULL OR ped_sucursal_id = CAST(:sucursalId AS BIGINT)) ";

        List<Object[]> rows = em.createNativeQuery("""
            SELECT ped_estado::text, COUNT(*), COALESCE(SUM(ped_total_centavos), 0)
            FROM pedidos
            """ + where + """
            GROUP BY ped_estado
            ORDER BY COUNT(*) DESC
            """)
            .unwrap(org.hibernate.query.NativeQuery.class)
            .setProperties(periodo.params())
            .setParameter("colaboradorId", colaboradorId)
            .setParameter("sucursalId", sucursalId)
            .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        long maxCantidad = rows.stream().mapToLong(r -> ((Number) r[1]).longValue()).max().orElse(1L);
        for (Object[] r : rows) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("estado", r[0]);
            item.put("cantidad", ((Number) r[1]).longValue());
            item.put("total", ((Number) r[2]).longValue() / 100L);
            item.put("porcentaje_grafica", ((Number) r[1]).longValue() * 100L / maxCantidad);
            result.add(item);
        }
        return result;
    }

    // ─── Productos más vendidos ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> productosMasVendidos(String mes, Long colaboradorId, Long sucursalId) {
        tenantSupport.applyTenant(em);
        Periodo periodo = periodo(mes);
        String where = (periodo.hasRange() ? "AND p.ped_creado_en >= :start AND p.ped_creado_en < :end " : "")
                + " AND (CAST(:colaboradorId AS BIGINT) IS NULL OR p.ped_colaborador_id = CAST(:colaboradorId AS BIGINT)) "
                + " AND (CAST(:sucursalId AS BIGINT) IS NULL OR p.ped_sucursal_id = CAST(:sucursalId AS BIGINT)) ";

        List<Object[]> rows = em.createNativeQuery("""
            SELECT
              pi.pi_prd_id,
              pi.pi_nombre_snap,
              SUM(pi.pi_cantidad)                       AS unidades,
              SUM(pi.pi_subtotal_centavos)              AS total_centavos,
              (SELECT pim.pi_url FROM producto_imagenes pim
               WHERE pim.pi_prd_id = pi.pi_prd_id AND pim.pi_tipo = 'imagen'
               ORDER BY pim.pi_orden ASC LIMIT 1)      AS imagen_url
            FROM pedido_items pi
            JOIN pedidos p ON p.ped_id = pi.pi_ped_id
            WHERE p.ped_estado IN ('preparando', 'enviado', 'entregado')
            """ + where + """
            GROUP BY pi.pi_prd_id, pi.pi_nombre_snap
            ORDER BY unidades DESC
            LIMIT 10
            """)
            .unwrap(org.hibernate.query.NativeQuery.class)
            .setProperties(periodo.params())
            .setParameter("colaboradorId", colaboradorId)
            .setParameter("sucursalId", sucursalId)
            .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(Map.of(
                    "prd_id",    ((Number) r[0]).longValue(),
                    "nombre",    r[1],
                    "unidades",  ((Number) r[2]).longValue(),
                    "total",     ((Number) r[3]).longValue() / 100L,
                    "imagen_url", r[4] != null ? r[4] : ""
            ));
        }
        return result;
    }

    // ─── Ventas por categoría ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ventasPorCategoria(String mes, Long colaboradorId, Long sucursalId) {
        tenantSupport.applyTenant(em);
        Periodo periodo = periodo(mes);
        String where = (periodo.hasRange() ? "AND p.ped_creado_en >= :start AND p.ped_creado_en < :end " : "")
                + " AND (CAST(:colaboradorId AS BIGINT) IS NULL OR p.ped_colaborador_id = CAST(:colaboradorId AS BIGINT)) "
                + " AND (CAST(:sucursalId AS BIGINT) IS NULL OR p.ped_sucursal_id = CAST(:sucursalId AS BIGINT)) ";

        List<Object[]> rows = em.createNativeQuery("""
            SELECT
              c.cat_nombre,
              SUM(pi.pi_subtotal_centavos) AS total_centavos,
              SUM(pi.pi_cantidad)          AS unidades
            FROM pedido_items pi
            JOIN pedidos p   ON p.ped_id   = pi.pi_ped_id
            JOIN productos pr ON pr.prd_id  = pi.pi_prd_id
            JOIN categorias c ON c.cat_id   = pr.prd_cat_id
            WHERE p.ped_estado IN ('preparando', 'enviado', 'entregado')
            """ + where + """
            GROUP BY c.cat_nombre
            ORDER BY total_centavos DESC
            """)
            .unwrap(org.hibernate.query.NativeQuery.class)
            .setProperties(periodo.params())
            .setParameter("colaboradorId", colaboradorId)
            .setParameter("sucursalId", sucursalId)
            .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        long maxTotal = rows.stream().mapToLong(r -> ((Number) r[1]).longValue()).max().orElse(1L);
        for (Object[] r : rows) {
            result.add(Map.of(
                    "categoria", r[0],
                    "total",     ((Number) r[1]).longValue() / 100L,
                    "unidades",  ((Number) r[2]).longValue(),
                    "porcentaje_grafica", ((Number) r[1]).longValue() * 100L / maxTotal
            ));
        }
        return result;
    }

    // ─── Ventas por canal (online vs. mostrador) ─────────────────────────────

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ventasPorCanal(String mes, Long colaboradorId, Long sucursalId) {
        tenantSupport.applyTenant(em);
        Periodo periodo = periodo(mes);
        String where = (periodo.hasRange() ? "AND p.ped_creado_en >= :start AND p.ped_creado_en < :end " : "")
                + " AND (CAST(:colaboradorId AS BIGINT) IS NULL OR p.ped_colaborador_id = CAST(:colaboradorId AS BIGINT)) "
                + " AND (CAST(:sucursalId AS BIGINT) IS NULL OR p.ped_sucursal_id = CAST(:sucursalId AS BIGINT)) ";

        // La subconsulta correlacionada de unidades (en vez de un JOIN directo a pedido_items)
        // evita el "fan-out": un JOIN normal multiplicaría ped_total_centavos por cada ítem del
        // pedido antes de sumarlo, inflando el total.
        List<Object[]> rows = em.createNativeQuery("""
            SELECT
              p.ped_canal::text,
              COUNT(*)                                AS pedidos,
              COALESCE(SUM(p.ped_total_centavos), 0)  AS total_centavos,
              COALESCE(SUM((SELECT SUM(pi.pi_cantidad) FROM pedido_items pi
                             WHERE pi.pi_ped_id = p.ped_id)), 0) AS unidades
            FROM pedidos p
            WHERE p.ped_estado IN ('preparando', 'enviado', 'entregado')
            """ + where + """
            GROUP BY p.ped_canal
            ORDER BY total_centavos DESC
            """)
            .unwrap(org.hibernate.query.NativeQuery.class)
            .setProperties(periodo.params())
            .setParameter("colaboradorId", colaboradorId)
            .setParameter("sucursalId", sucursalId)
            .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(Map.of(
                    "canal",    r[0],
                    "pedidos",  ((Number) r[1]).longValue(),
                    "total",    ((Number) r[2]).longValue() / 100L,
                    "unidades", ((Number) r[3]).longValue()
            ));
        }
        return result;
    }

    private Periodo periodo(String mes) {
        if (mes == null || mes.isBlank()) return new Periodo(null, null);
        YearMonth ym = YearMonth.parse(mes);
        OffsetDateTime start = ym.atDay(1).atStartOfDay(BOGOTA).toOffsetDateTime();
        OffsetDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay(BOGOTA).toOffsetDateTime();
        return new Periodo(start, end);
    }

    private record Periodo(OffsetDateTime start, OffsetDateTime end) {
        boolean hasRange() {
            return start != null && end != null;
        }

        Map<String, Object> params() {
            return hasRange() ? Map.of("start", start, "end", end) : Map.of();
        }
    }
}
