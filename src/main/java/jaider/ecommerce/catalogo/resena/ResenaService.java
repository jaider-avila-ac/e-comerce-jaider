package jaider.ecommerce.catalogo.resena;

import jaider.ecommerce.catalogo.CatalogCacheService;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ResenaService {

    private final ResenaRepository resenaRepository;
    private final TenantSupport tenantSupport;
    private final ResenaCacheService resenaCache;
    private final CatalogCacheService catalogCache;

    @PersistenceContext
    private EntityManager em;

    // ─── Lectura pública ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ResenaListResponse listarPublicas(Long prdId) {
        tenantSupport.applyTenant(em);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT r.res_id, r.res_calificacion, r.res_titulo, r.res_cuerpo, r.res_creado_en,
                       cp.cp_nombre, cp.cp_apellido
                FROM reseñas r
                LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = r.res_usr_id
                WHERE r.res_prd_id = :prdId AND r.res_aprobada = true
                ORDER BY r.res_creado_en DESC
                """)
                .setParameter("prdId", prdId)
                .getResultList();

        List<ResenaResponse> items = rows.stream().map(row -> new ResenaResponse(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).intValue(),
                (String) row[2],
                (String) row[3],
                autorNombre((String) row[5], (String) row[6]),
                toOffsetDateTime(row[4])
        )).toList();

        if (items.isEmpty()) {
            return new ResenaListResponse(null, 0, List.of(), items);
        }
        double promedio = items.stream().mapToInt(ResenaResponse::calificacion).average().orElse(0);
        double redondeado = Math.round(promedio * 10) / 10.0;
        List<ResenaListResponse.Distribucion> distribucion = java.util.stream.IntStream
                .iterate(5, n -> n >= 1, n -> n - 1)
                .mapToObj(estrellas -> {
                    long cantidad = items.stream().filter(i -> i.calificacion() == estrellas).count();
                    return new ResenaListResponse.Distribucion(
                            estrellas, cantidad, Math.round(cantidad * 100.0 / items.size()));
                }).toList();
        return new ResenaListResponse(redondeado, items.size(), distribucion, items);
    }

    /** Carga masiva de promedio/total por producto — evita N+1 al enriquecer el catálogo. */
    @Transactional(readOnly = true)
    public Map<Long, ResenaResumen> resumenBulk(List<Long> prdIds) {
        if (prdIds.isEmpty()) return Map.of();
        tenantSupport.applyTenant(em);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT res_prd_id, ROUND(AVG(res_calificacion)::numeric, 1), COUNT(*)
                FROM reseñas
                WHERE res_prd_id IN :ids AND res_aprobada = true
                GROUP BY res_prd_id
                """)
                .setParameter("ids", prdIds)
                .getResultList();

        Map<Long, ResenaResumen> result = new HashMap<>();
        for (Object[] row : rows) {
            Long prdId = ((Number) row[0]).longValue();
            double promedio = ((Number) row[1]).doubleValue();
            int total = ((Number) row[2]).intValue();
            result.put(prdId, new ResenaResumen(promedio, total));
        }
        return result;
    }

    // ─── Estado del usuario autenticado ────────────────────────────────────

    @Transactional(readOnly = true)
    public ResenaEstadoResponse estadoParaUsuario(Long prdId, Long usrId, Long tndId) {
        tenantSupport.applyTenant(em);
        boolean compro = buscarPedidoItemCompraAprobada(prdId, usrId, tndId) != null;
        boolean yaReseno = resenaRepository.existsByPrdIdAndUsrId(prdId, usrId);
        return new ResenaEstadoResponse(compro, yaReseno);
    }

    // ─── Creación ───────────────────────────────────────────────────────────

    @Transactional
    public ResenaResponse crear(Long prdId, Long usrId, Long tndId, ResenaRequest req) {
        tenantSupport.applyTenant(em);

        if (req.calificacion() == null || req.calificacion() < 1 || req.calificacion() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La calificación debe ser entre 1 y 5 estrellas");
        }
        if (resenaRepository.existsByPrdIdAndUsrId(prdId, usrId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya reseñaste este producto");
        }
        Long piId = buscarPedidoItemCompraAprobada(prdId, usrId, tndId);
        if (piId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Debes haber comprado este producto para reseñarlo");
        }

        Resena resena = new Resena();
        resena.setPrdId(prdId);
        resena.setUsrId(usrId);
        resena.setPiId(piId);
        resena.setCalificacion(req.calificacion());
        resena.setTitulo(blankToNull(req.titulo()));
        resena.setCuerpo(blankToNull(req.cuerpo()));
        resena.setAprobada(true);
        resena = resenaRepository.save(resena);

        // Invalida el caché de reseñas del producto y el del catálogo (donde va embebido
        // el promedio/total) — así la nueva reseña se ve de inmediato, no hasta que expire el TTL.
        resenaCache.invalidate(String.valueOf(tndId));
        catalogCache.invalidate(String.valueOf(tndId));

        return new ResenaResponse(
                resena.getId(), resena.getCalificacion(), resena.getTitulo(), resena.getCuerpo(),
                "Tú", java.time.OffsetDateTime.now()
        );
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Long buscarPedidoItemCompraAprobada(Long prdId, Long usrId, Long tndId) {
        List<Number> rows = em.createNativeQuery("""
                SELECT pi.pi_id
                FROM pedido_items pi
                JOIN pedidos p ON p.ped_id = pi.pi_ped_id
                JOIN pagos pg ON pg.pag_ped_id = p.ped_id AND pg.pag_estado = CAST('APPROVED' AS estado_pago)
                WHERE pi.pi_prd_id = :prdId AND p.ped_usr_id = :usrId AND p.ped_tnd_id = :tndId
                ORDER BY p.ped_creado_en DESC
                LIMIT 1
                """)
                .setParameter("prdId", prdId)
                .setParameter("usrId", usrId)
                .setParameter("tndId", tndId)
                .getResultList();
        if (rows.isEmpty()) return null;
        return rows.get(0).longValue();
    }

    private static String autorNombre(String nombre, String apellido) {
        if (nombre == null || nombre.isBlank()) return "Cliente Calzacaribe";
        String inicialApellido = (apellido != null && !apellido.isBlank())
                ? " " + Character.toUpperCase(apellido.charAt(0)) + "."
                : "";
        return nombre + inicialApellido;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static java.time.OffsetDateTime toOffsetDateTime(Object value) {
        return switch (value) {
            case java.time.Instant instant -> instant.atOffset(java.time.ZoneOffset.UTC);
            case java.sql.Timestamp ts -> ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
            case java.time.OffsetDateTime odt -> odt;
            case null, default -> null;
        };
    }

    public record ResenaResumen(Double promedio, Integer total) {}
}
