package jaider.ecommerce.analitica;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Captura de tráfico (visitantes anónimos y registrados) + los reportes que se arman con eso:
 * resumen de visitas, horarios pico (generales y por producto), productos más vistos, y el
 * embudo visitante→agregó al carrito→compró.
 *
 * Tabla propia (visitas), separada de eventos_usuario — esa exige un usr_id real (FK NOT NULL),
 * no puede representar un visitante anónimo. Ver db-local/crear_tabla_visitas.sql.
 */
@Service
@RequiredArgsConstructor
public class VisitaService {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final Set<String> TIPOS_VALIDOS = Set.of("pagina_vista", "vista_producto", "agregar_carrito");
    private static final Set<String> DISPOSITIVOS_VALIDOS = Set.of("movil", "escritorio");
    private static final int VENTANA_DEFECTO_DIAS = 30;

    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    // ─── Captura ────────────────────────────────────────────────────────────

    @Transactional
    public void registrar(VisitaEventoRequest req, Long usrId) {
        tenantSupport.requireTenant(em);
        String tndIdStr = TenantContext.get();
        if (tndIdStr == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant no identificado");

        if (req.visitorId() == null || req.visitorId().isBlank() || req.visitorId().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visitorId inválido");
        }
        if (req.tipo() == null || !TIPOS_VALIDOS.contains(req.tipo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tipo inválido");
        }
        // Un dispositivo con un valor raro no debe tumbar el evento entero — se guarda como
        // "no especificado" en vez de rechazarlo (el navegador nunca debería mandar otra cosa,
        // pero tampoco vale la pena perder la visita completa por eso).
        String dispositivo = req.dispositivo() != null && DISPOSITIVOS_VALIDOS.contains(req.dispositivo())
                ? req.dispositivo() : null;
        String ruta = req.ruta() != null && req.ruta().length() > 255 ? req.ruta().substring(0, 255) : req.ruta();

        em.createNativeQuery("""
                INSERT INTO visitas
                    (vis_tnd_id, vis_visitor_id, vis_usr_id, vis_tipo, vis_entidad_tipo, vis_entidad_id, vis_ruta, vis_dispositivo)
                VALUES
                    (:tndId, :visitorId, :usrId, :tipo, :entidadTipo, :entidadId, :ruta, :dispositivo)
                """)
                .setParameter("tndId", Long.parseLong(tndIdStr))
                .setParameter("visitorId", req.visitorId())
                .setParameter("usrId", usrId)
                .setParameter("tipo", req.tipo())
                .setParameter("entidadTipo", req.entidadTipo())
                .setParameter("entidadId", req.entidadId())
                .setParameter("ruta", ruta)
                .setParameter("dispositivo", dispositivo)
                .executeUpdate();
    }

    // ─── Reportes (admin) ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> resumen(String desde, String hasta) {
        tenantSupport.requireTenant(em);
        Rango rango = rango(desde, hasta);

        Object[] fila = (Object[]) em.createNativeQuery("""
                SELECT
                  COUNT(*) FILTER (WHERE vis_tipo = 'pagina_vista')                          AS total_visitas,
                  COUNT(DISTINCT vis_visitor_id)                                             AS visitantes_unicos,
                  COUNT(DISTINCT vis_visitor_id) FILTER (WHERE vis_usr_id IS NOT NULL)        AS visitantes_registrados
                FROM visitas
                WHERE vis_creado_en >= :start AND vis_creado_en < :end
                """)
                .setParameter("start", rango.start())
                .setParameter("end", rango.end())
                .getSingleResult();

        long totalVisitas = ((Number) fila[0]).longValue();
        long visitantesUnicos = ((Number) fila[1]).longValue();
        long visitantesRegistrados = ((Number) fila[2]).longValue();

        List<?> filasPorDia = em.createNativeQuery("""
                SELECT (vis_creado_en AT TIME ZONE 'America/Bogota')::date AS dia,
                       COUNT(*) FILTER (WHERE vis_tipo = 'pagina_vista')
                FROM visitas
                WHERE vis_creado_en >= :start AND vis_creado_en < :end
                GROUP BY dia
                ORDER BY dia
                """)
                .setParameter("start", rango.start())
                .setParameter("end", rango.end())
                .getResultList();

        List<Map<String, Object>> porDia = new ArrayList<>();
        for (Object o : filasPorDia) {
            Object[] f = (Object[]) o;
            porDia.add(Map.of("fecha", f[0].toString(), "cantidad", ((Number) f[1]).longValue()));
        }

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("total_visitas", totalVisitas);
        resultado.put("visitantes_unicos", visitantesUnicos);
        resultado.put("visitantes_registrados", visitantesRegistrados);
        resultado.put("visitantes_anonimos", visitantesUnicos - visitantesRegistrados);
        resultado.put("por_dia", porDia);
        return resultado;
    }

    /** Distribución por hora del día (0-23, hora de Bogotá) — de todo el tráfico si productoId
     *  es null, o solo de las vistas de ESE producto si viene con valor. Siempre devuelve las 24
     *  horas (con 0 en las que no hubo nada), para que el gráfico no quede con huecos. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> porHora(String desde, String hasta, Long productoId) {
        tenantSupport.requireTenant(em);
        Rango rango = rango(desde, hasta);

        String filtroTipo = productoId != null
                ? "vis_tipo = 'vista_producto' AND vis_entidad_id = :productoId"
                : "vis_tipo = 'pagina_vista'";

        Query query = em.createNativeQuery("""
                SELECT EXTRACT(HOUR FROM vis_creado_en AT TIME ZONE 'America/Bogota')::int AS hora, COUNT(*)
                FROM visitas
                WHERE vis_creado_en >= :start AND vis_creado_en < :end AND \s""" + filtroTipo + """
                \s GROUP BY hora
                """)
                .setParameter("start", rango.start())
                .setParameter("end", rango.end());
        if (productoId != null) query.setParameter("productoId", productoId);

        Map<Integer, Long> conteos = new HashMap<>();
        for (Object o : query.getResultList()) {
            Object[] f = (Object[]) o;
            conteos.put(((Number) f[0]).intValue(), ((Number) f[1]).longValue());
        }
        List<Map<String, Object>> resultado = new ArrayList<>();
        for (int hora = 0; hora < 24; hora++) {
            resultado.add(Map.of("hora", hora, "cantidad", conteos.getOrDefault(hora, 0L)));
        }
        return resultado;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> productosMasVistos(String desde, String hasta, int limit) {
        tenantSupport.requireTenant(em);
        Rango rango = rango(desde, hasta);

        List<?> filas = em.createNativeQuery("""
                SELECT v.vis_entidad_id, p.prd_nombre, COUNT(*) AS vistas
                FROM visitas v
                JOIN productos p ON p.prd_id = v.vis_entidad_id
                WHERE v.vis_tipo = 'vista_producto'
                  AND v.vis_creado_en >= :start AND v.vis_creado_en < :end
                GROUP BY v.vis_entidad_id, p.prd_nombre
                ORDER BY vistas DESC
                LIMIT :limit
                """)
                .setParameter("start", rango.start())
                .setParameter("end", rango.end())
                .setParameter("limit", limit)
                .getResultList();

        List<Map<String, Object>> resultado = new ArrayList<>();
        for (Object o : filas) {
            Object[] f = (Object[]) o;
            resultado.add(Map.of(
                    "producto_id", ((Number) f[0]).longValue(),
                    "nombre", f[1],
                    "vistas", ((Number) f[2]).longValue()
            ));
        }
        return resultado;
    }

    /** Aproximado a propósito: los pedidos no guardan qué visitor_id los originó (no hay
     *  atribución exacta visitante→pedido todavía), así que "pedidos" es el total del período,
     *  no un conteo de visitantes distintos que sí compraron — la tasa de conversión es una
     *  aproximación razonable (pedidos / visitantes), no una cifra exacta por persona. */
    @Transactional(readOnly = true)
    public Map<String, Object> embudoCarrito(String desde, String hasta) {
        tenantSupport.requireTenant(em);
        Rango rango = rango(desde, hasta);

        Object[] fila = (Object[]) em.createNativeQuery("""
                SELECT
                  COUNT(DISTINCT vis_visitor_id) FILTER (WHERE vis_tipo = 'pagina_vista')       AS visitantes,
                  COUNT(DISTINCT vis_visitor_id) FILTER (WHERE vis_tipo = 'agregar_carrito')     AS agregaron_carrito
                FROM visitas
                WHERE vis_creado_en >= :start AND vis_creado_en < :end
                """)
                .setParameter("start", rango.start())
                .setParameter("end", rango.end())
                .getSingleResult();

        long visitantes = ((Number) fila[0]).longValue();
        long agregaronCarrito = ((Number) fila[1]).longValue();

        Number filaPedidos = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM pedidos WHERE ped_creado_en >= :start AND ped_creado_en < :end
                """)
                .setParameter("start", rango.start())
                .setParameter("end", rango.end())
                .getSingleResult();
        long pedidos = filaPedidos.longValue();

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("visitantes", visitantes);
        resultado.put("agregaron_carrito", agregaronCarrito);
        resultado.put("pedidos", pedidos);
        resultado.put("tasa_agregado_carrito", tasaPorcentaje(agregaronCarrito, visitantes));
        resultado.put("tasa_conversion", tasaPorcentaje(pedidos, visitantes));
        return resultado;
    }

    private double tasaPorcentaje(long parte, long total) {
        if (total <= 0) return 0.0;
        return Math.round((double) parte / total * 1000) / 10.0;
    }

    // ─── Fechas ─────────────────────────────────────────────────────────────

    private Rango rango(String desde, String hasta) {
        LocalDate hoy = LocalDate.now(BOGOTA);
        LocalDate fin = (hasta != null && !hasta.isBlank() ? LocalDate.parse(hasta) : hoy).plusDays(1);
        LocalDate inicio = desde != null && !desde.isBlank() ? LocalDate.parse(desde) : fin.minusDays(VENTANA_DEFECTO_DIAS + 1);
        return new Rango(inicio.atStartOfDay(BOGOTA).toOffsetDateTime(), fin.atStartOfDay(BOGOTA).toOffsetDateTime());
    }

    private record Rango(OffsetDateTime start, OffsetDateTime end) {}
}
