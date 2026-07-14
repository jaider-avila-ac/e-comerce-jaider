package jaider.ecommerce.pedido;

import com.fasterxml.jackson.databind.ObjectMapper;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.usuario.cliente.ClienteDireccionRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crea el pedido y sus ítems como snapshot congelado del carrito en el momento del checkout.
 * Bean separado de PedidoCheckoutService para que sus métodos @Transactional se invoquen
 * siempre a través del proxy de Spring (evita el problema de auto-invocación).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PedidoCreacionService {

    private final TenantSupport tenantSupport;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Value("${app.shipping.flat-cost-centavos}")
    private long shippingFlatCostCentavos;

    private static final SecureRandom RANDOM = new SecureRandom();

    public record ItemCarrito(Long prdId, Long varId, int cantidad, long precioCentavos,
                               String nombre, String imagen, String talla, String color) {
    }

    public record PedidoCreado(Long pedId, String numero, long totalCentavos) {
    }

    @Transactional
    public PedidoCreado crearDesdeCarrito(Long usrId, Long tndId, Long direccionId,
                                           ClienteDireccionRequest direccionInline, String notas) {
        tenantSupport.applyTenant(em);

        List<ItemCarrito> items = cargarCarritoValidado(usrId);
        Map<String, Object> dirSnapshot = resolverDireccion(usrId, tndId, direccionId, direccionInline);

        long subtotal = items.stream().mapToLong(i -> i.precioCentavos() * i.cantidad()).sum();
        Object[] envioConfig = (Object[]) em.createNativeQuery("""
                SELECT tnd_envio_gratis_activo, tnd_envio_gratis_desde_centavos
                FROM tiendas WHERE tnd_id = :tndId
                """).setParameter("tndId", tndId).getSingleResult();
        boolean envioGratis = Boolean.TRUE.equals(envioConfig[0])
                && subtotal >= ((Number) envioConfig[1]).longValue();
        long envio = envioGratis ? 0L : shippingFlatCostCentavos;
        long total = subtotal + envio;
        String numero = generarNumeroUnico();

        Number pedIdNum = (Number) em.createNativeQuery("""
                INSERT INTO pedidos (ped_tnd_id, ped_usr_id, ped_numero, ped_dir_snapshot,
                                      ped_subtotal_centavos, ped_envio_centavos, ped_total_centavos, ped_notas)
                VALUES (:tndId, :usrId, :numero, CAST(:dirSnapshot AS jsonb), :subtotal, :envio, :total, :notas)
                RETURNING ped_id
                """)
                .setParameter("tndId", tndId)
                .setParameter("usrId", usrId)
                .setParameter("numero", numero)
                .setParameter("dirSnapshot", toJson(dirSnapshot))
                .setParameter("subtotal", subtotal)
                .setParameter("envio", envio)
                .setParameter("total", total)
                .setParameter("notas", (notas != null && !notas.isBlank()) ? notas.trim() : null)
                .getSingleResult();
        Long pedId = pedIdNum.longValue();

        for (ItemCarrito item : items) {
            Map<String, Object> variantesSnap = new LinkedHashMap<>();
            if (item.talla() != null) variantesSnap.put("talla", item.talla());
            if (item.color() != null) variantesSnap.put("color", item.color());

            em.createNativeQuery("""
                    INSERT INTO pedido_items (pi_ped_id, pi_prd_id, pi_var_id, pi_nombre_snap, pi_imagen_snap,
                                               pi_variantes_snap, pi_precio_unitario_centavos, pi_cantidad, pi_subtotal_centavos)
                    VALUES (:pedId, :prdId, :varId, :nombre, :imagen, CAST(:variantesSnap AS jsonb), :precio, :cantidad, :subtotal)
                    """)
                    .setParameter("pedId", pedId)
                    .setParameter("prdId", item.prdId())
                    .setParameter("varId", item.varId())
                    .setParameter("nombre", item.nombre())
                    .setParameter("imagen", item.imagen())
                    .setParameter("variantesSnap", toJson(variantesSnap))
                    .setParameter("precio", item.precioCentavos())
                    .setParameter("cantidad", item.cantidad())
                    .setParameter("subtotal", item.precioCentavos() * item.cantidad())
                    .executeUpdate();
        }

        em.createNativeQuery("""
                INSERT INTO pedido_historial_estados (phe_ped_id, phe_estado)
                VALUES (:pedId, CAST('pendiente_pago' AS estado_pedido))
                """)
                .setParameter("pedId", pedId)
                .executeUpdate();

        log.info("[Checkout] Pedido {} ({}) creado para usuario {} — total {} centavos", pedId, numero, usrId, total);
        return new PedidoCreado(pedId, numero, total);
    }

    @Transactional
    public Long crearPago(Long pedId, Long usrId, String referencia, long montoCentavos, String metodo) {
        tenantSupport.applyTenant(em);
        Number pagIdNum = (Number) em.createNativeQuery("""
                INSERT INTO pagos (pag_ped_id, pag_usr_id, pag_referencia, pag_proveedor, pag_metodo, pag_monto_centavos)
                VALUES (:pedId, :usrId, :referencia, CAST('WOMPI' AS proveedor_pago), CAST(:metodo AS metodo_pago), :monto)
                RETURNING pag_id
                """)
                .setParameter("pedId", pedId)
                .setParameter("usrId", usrId)
                .setParameter("referencia", referencia)
                .setParameter("metodo", metodo)
                .setParameter("monto", montoCentavos)
                .getSingleResult();
        return pagIdNum.longValue();
    }

    @Transactional(readOnly = true)
    public String obtenerEmail(Long usrId) {
        tenantSupport.applyTenant(em);
        return (String) em.createNativeQuery("SELECT usr_email FROM usuarios WHERE usr_id = :id")
                .setParameter("id", usrId)
                .getSingleResult();
    }

    /** Estado del pedido y su último pago, para que el frontend haga polling tras el checkout. */
    @Transactional(readOnly = true)
    public Map<String, Object> consultarEstado(Long usrId, Long tndId, String numero) {
        tenantSupport.applyTenant(em);

        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery("""
                    SELECT ped_id, ped_numero, ped_estado::text, ped_total_centavos, ped_creado_en, ped_link_seguimiento
                    FROM pedidos WHERE ped_numero = :numero AND ped_usr_id = :usrId AND ped_tnd_id = :tndId
                    """)
                    .setParameter("numero", numero)
                    .setParameter("usrId", usrId)
                    .setParameter("tndId", tndId)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }

        Long pedId = ((Number) row[0]).longValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", pedId);
        result.put("numero", row[1]);
        result.put("estado", row[2]);
        result.put("total", ((Number) row[3]).longValue() / 100L);
        result.put("creado_en", row[4]);
        result.put("link_seguimiento", row[5]);

        @SuppressWarnings("unchecked")
        List<Object[]> pagos = em.createNativeQuery("""
                SELECT pag_estado::text, pag_metodo::text, pag_motivo_rechazo
                FROM pagos WHERE pag_ped_id = :pedId ORDER BY pag_id DESC LIMIT 1
                """)
                .setParameter("pedId", pedId)
                .getResultList();
        if (!pagos.isEmpty()) {
            Object[] p = pagos.get(0);
            result.put("pago_estado", p[0]);
            result.put("pago_metodo", p[1]);
            result.put("pago_motivo_rechazo", p[2]);
        }
        return result;
    }

    // ── Carrito → ítems validados ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarComprasAprobadas(Long usrId, Long tndId) {
        tenantSupport.applyTenant(em);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT DISTINCT p.ped_id, p.ped_numero, p.ped_estado::text, p.ped_subtotal_centavos,
                       p.ped_descuento_centavos, p.ped_envio_centavos, p.ped_total_centavos,
                       p.ped_dir_snapshot::text, p.ped_notas, p.ped_alerta_stock, p.ped_link_seguimiento,
                       p.ped_creado_en
                FROM pedidos p
                JOIN pagos pg ON pg.pag_ped_id = p.ped_id AND pg.pag_estado = CAST('APPROVED' AS estado_pago)
                WHERE p.ped_usr_id = :usrId AND p.ped_tnd_id = :tndId
                ORDER BY p.ped_creado_en DESC
                """)
                .setParameter("usrId", usrId)
                .setParameter("tndId", tndId)
                .getResultList();

        if (rows.isEmpty()) return List.of();

        List<Long> pedIds = rows.stream().map(r -> ((Number) r[0]).longValue()).toList();
        Map<Long, List<Map<String, Object>>> itemsPorPedido = cargarItemsPorPedido(pedIds);

        List<Map<String, Object>> compras = new ArrayList<>();
        for (Object[] row : rows) {
            Long pedId = ((Number) row[0]).longValue();
            Map<String, Object> compra = new LinkedHashMap<>();
            compra.put("id", pedId);
            compra.put("numero", row[1]);
            compra.put("estado", row[2]);
            compra.put("subtotal", ((Number) row[3]).longValue() / 100L);
            compra.put("descuento", ((Number) row[4]).longValue() / 100L);
            compra.put("envio", ((Number) row[5]).longValue() / 100L);
            compra.put("total", ((Number) row[6]).longValue() / 100L);
            compra.put("dir_snapshot", fromJson((String) row[7]));
            compra.put("notas", row[8]);
            compra.put("alerta_stock", row[9]);
            compra.put("link_seguimiento", row[10]);
            compra.put("creado_en", row[11]);
            compra.put("items", itemsPorPedido.getOrDefault(pedId, List.of()));
            compras.add(compra);
        }
        return compras;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, List<Map<String, Object>>> cargarItemsPorPedido(List<Long> pedIds) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT pi_ped_id, pi_prd_id, pi_nombre_snap, pi_imagen_snap, pi_variantes_snap::text,
                       pi_precio_unitario_centavos, pi_cantidad
                FROM pedido_items WHERE pi_ped_id IN :pedIds ORDER BY pi_id ASC
                """)
                .setParameter("pedIds", pedIds)
                .getResultList();

        Map<Long, List<Map<String, Object>>> porPedido = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Long pedId = ((Number) r[0]).longValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("producto_id", r[1]);
            item.put("nombre", r[2]);
            item.put("imagen", r[3]);
            item.put("variantes", fromJson((String) r[4]));
            item.put("precio", ((Number) r[5]).longValue() / 100L);
            item.put("cantidad", r[6]);
            porPedido.computeIfAbsent(pedId, k -> new ArrayList<>()).add(item);
        }
        return porPedido;
    }

    @SuppressWarnings("unchecked")
    private List<ItemCarrito> cargarCarritoValidado(Long usrId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT ci.ci_prd_id, ci.ci_var_id, ci.ci_cantidad, ci.ci_precio_snap_centavos,
                       p.prd_nombre, p.prd_activo, v.var_talla, v.var_color,
                       COALESCE(
                           (SELECT pi_url FROM producto_imagenes
                             WHERE pi_prd_id = p.prd_id AND pi_tipo = 'imagen' AND pi_var_id = ci.ci_var_id
                             ORDER BY pi_orden ASC LIMIT 1),
                           (SELECT pi_url FROM producto_imagenes
                             WHERE pi_prd_id = p.prd_id AND pi_tipo = 'imagen'
                             ORDER BY pi_orden ASC LIMIT 1)
                       ) AS imagen,
                       COALESCE(
                           v.var_stock,
                           (SELECT COALESCE(SUM(var_stock), 0) FROM variantes
                             WHERE var_prd_id = p.prd_id AND var_activo = true)
                       ) AS stock_disponible
                FROM carrito_items ci
                JOIN carritos c ON c.car_id = ci.ci_car_id
                JOIN productos p ON p.prd_id = ci.ci_prd_id
                LEFT JOIN variantes v ON v.var_id = ci.ci_var_id
                WHERE c.car_usr_id = :usrId
                ORDER BY ci.ci_agregado_en ASC
                """)
                .setParameter("usrId", usrId)
                .getResultList();

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El carrito está vacío");
        }

        List<ItemCarrito> items = new ArrayList<>();
        for (Object[] r : rows) {
            Long prdId = ((Number) r[0]).longValue();
            Long varId = r[1] != null ? ((Number) r[1]).longValue() : null;
            int cantidad = ((Number) r[2]).intValue();
            long precio = ((Number) r[3]).longValue();
            String nombre = (String) r[4];
            boolean activo = Boolean.TRUE.equals(r[5]);
            String talla = (String) r[6];
            String color = (String) r[7];
            String imagen = (String) r[8];
            int stockDisponible = ((Number) r[9]).intValue();

            if (!activo) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El producto \"" + nombre + "\" ya no está disponible");
            }
            if (stockDisponible < cantidad) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No hay suficiente stock de \"" + nombre + "\" (disponible: " + stockDisponible + ")");
            }

            items.add(new ItemCarrito(prdId, varId, cantidad, precio, nombre, imagen, talla, color));
        }
        return items;
    }

    // ── Dirección de envío ───────────────────────────────────────────────────

    private Map<String, Object> resolverDireccion(Long usrId, Long tndId, Long direccionId,
                                                    ClienteDireccionRequest inline) {
        if (direccionId != null) {
            Object[] row;
            try {
                row = (Object[]) em.createNativeQuery("""
                        SELECT cd_direccion, cd_complemento, cd_departamento, cd_municipio,
                               cd_barrio, cd_apartamento, cd_contacto_nombre, cd_contacto_telefono
                        FROM clientes_direcciones
                        WHERE cd_id = :id AND cd_usr_id = :usrId AND cd_tnd_id = :tndId
                        """)
                        .setParameter("id", direccionId)
                        .setParameter("usrId", usrId)
                        .setParameter("tndId", tndId)
                        .getSingleResult();
            } catch (NoResultException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dirección no encontrada");
            }
            return direccionMap((String) row[0], (String) row[1], (String) row[2], (String) row[3],
                    (String) row[4], (String) row[5], (String) row[6], (String) row[7]);
        }

        if (inline != null && inline.direccion() != null && !inline.direccion().isBlank()) {
            return direccionMap(inline.direccion(), inline.complemento(), inline.departamento(),
                    inline.municipio(), inline.barrio(), inline.apartamento(),
                    inline.contactoNombre(), inline.contactoTelefono());
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar una dirección de envío");
    }

    private Map<String, Object> direccionMap(String direccion, String complemento, String departamento,
                                              String municipio, String barrio, String apartamento,
                                              String contactoNombre, String contactoTelefono) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("direccion", direccion);
        m.put("complemento", complemento);
        m.put("departamento", departamento);
        m.put("municipio", municipio);
        m.put("barrio", barrio);
        m.put("apartamento", apartamento);
        m.put("contacto_nombre", contactoNombre);
        m.put("contacto_telefono", contactoTelefono);
        return m;
    }

    // ── Número de pedido ─────────────────────────────────────────────────────

    private String generarNumeroUnico() {
        for (int i = 0; i < 5; i++) {
            String candidato = "PED" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + String.format("%04d", RANDOM.nextInt(10_000));
            Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM pedidos WHERE ped_numero = :n")
                    .setParameter("n", candidato)
                    .getSingleResult();
            if (count.longValue() == 0) return candidato;
        }
        throw new IllegalStateException("No se pudo generar un número de pedido único");
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data != null ? data : Map.of());
        } catch (Exception e) {
            log.warn("No se pudo serializar JSON de pedido: {}", e.getMessage());
            return "{}";
        }
    }

    /** Convierte el texto de una columna jsonb (leída con ::text) a un mapa navegable,
     *  en vez de dejarlo como string plano en la respuesta al frontend. */
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("No se pudo parsear JSON de pedido: {}", e.getMessage());
            return Map.of();
        }
    }
}
