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
    public ReporteResumenResponse resumen(String mes, boolean incluirFinanciero) {
        tenantSupport.applyTenant(em);
        Periodo periodo = periodo(mes);
        String pedidosWhere = periodo.hasRange() ? " WHERE ped_creado_en >= :start AND ped_creado_en < :end " : "";
        String clientesWhere = periodo.hasRange() ? " WHERE usr_creado_en >= :start AND usr_creado_en < :end " : "";

        Object[] row = (Object[]) em.createNativeQuery("""
            SELECT
              COALESCE(SUM(CASE WHEN ped_estado IN ('preparando', 'enviado', 'entregado')
                                 THEN ped_total_centavos END), 0)                                AS total_ingresos,
              COUNT(*)                                                                          AS total_pedidos,
              COUNT(*) FILTER (WHERE ped_estado IN ('pagado', 'preparando', 'enviado')) AS en_proceso
            FROM pedidos
            """ + pedidosWhere)
            .unwrap(org.hibernate.query.NativeQuery.class)
            .setProperties(periodo.params())
            .getSingleResult();

        long totalIngresosCentavos = ((Number) row[0]).longValue();
        long totalPedidos          = ((Number) row[1]).longValue();
        long pedidosEnProceso      = ((Number) row[2]).longValue();

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

        // Colaborador/bodega no ven cifras de ingresos de la empresa — se redacta acá (en el
        // backend), no solo ocultando la tarjeta en el frontend, para que no quede expuesto
        // igual llamando al endpoint directamente.
        Long ingresos = incluirFinanciero ? totalIngresosCentavos / 100L : null;
        Long ticket    = incluirFinanciero ? ticketPromedio : null;

        return new ReporteResumenResponse(
                ingresos,
                ingresos,
                totalPedidos,
                totalPedidos,
                pedidosEnProceso,
                ticket,
                totalClientes,
                totalClientes,
                totalProductos,
                productosActivos
        );
    }

    // ─── Pedidos por estado ───────────────────────────────────────────────────

    // El Dashboard (todo el staff) y Reportes (solo admin) comparten este endpoint — igual que en
    // resumen(), se redacta "total" (ingresos por estado) para quien no sea admin/superadmin, en
    // vez de bloquear todo el endpoint, porque el Dashboard necesita el conteo por estado
    // (no financiero) para cualquier rol.
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> pedidosPorEstado(String mes, boolean incluirFinanciero) {
        tenantSupport.applyTenant(em);
        Periodo periodo = periodo(mes);
        String where = periodo.hasRange() ? "WHERE ped_creado_en >= :start AND ped_creado_en < :end " : "";

        List<Object[]> rows = em.createNativeQuery("""
            SELECT ped_estado::text, COUNT(*), COALESCE(SUM(ped_total_centavos), 0)
            FROM pedidos
            """ + where + """
            GROUP BY ped_estado
            ORDER BY COUNT(*) DESC
            """)
            .unwrap(org.hibernate.query.NativeQuery.class)
            .setProperties(periodo.params())
            .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        long maxCantidad = rows.stream().mapToLong(r -> ((Number) r[1]).longValue()).max().orElse(1L);
        for (Object[] r : rows) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("estado", r[0]);
            item.put("cantidad", ((Number) r[1]).longValue());
            item.put("total", incluirFinanciero ? ((Number) r[2]).longValue() / 100L : null);
            item.put("porcentaje_grafica", ((Number) r[1]).longValue() * 100L / maxCantidad);
            result.add(item);
        }
        return result;
    }

    // ─── Productos más vendidos ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> productosMasVendidos(String mes) {
        tenantSupport.applyTenant(em);
        Periodo periodo = periodo(mes);
        String where = periodo.hasRange()
                ? "AND p.ped_creado_en >= :start AND p.ped_creado_en < :end "
                : "";

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
    public List<Map<String, Object>> ventasPorCategoria(String mes) {
        tenantSupport.applyTenant(em);
        Periodo periodo = periodo(mes);
        String where = periodo.hasRange()
                ? "AND p.ped_creado_en >= :start AND p.ped_creado_en < :end "
                : "";

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
