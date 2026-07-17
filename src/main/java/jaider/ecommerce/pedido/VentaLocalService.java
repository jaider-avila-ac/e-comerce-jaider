package jaider.ecommerce.pedido;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Venta registrada en persona por un admin/colaborador (mostrador), sin pasar por carrito,
 * dirección de envío ni pasarela de pago — paralelo a {@link PedidoCreacionService}, que está
 * atado al checkout online.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VentaLocalService {

    private final TenantSupport tenantSupport;
    private final AuditoriaService auditoriaService;

    @PersistenceContext
    private EntityManager em;

    private static final SecureRandom RANDOM = new SecureRandom();

    public record VentaLocalCreada(Long pedidoId, String numero) {}

    public record ItemCotizado(Long prdId, Long varId, Integer cantidad, Long precio, Long subtotal,
                               String nombre) {}
    public record CotizacionVentaLocal(List<ItemCotizado> items, Long total) {}

    private record ItemResuelto(Long prdId, Long varId, int cantidad, long precioCentavos, String nombre, String imagen) {}

    @Transactional(readOnly = true)
    public CotizacionVentaLocal cotizar(List<VentaLocalRequest.ItemVentaLocal> pedidos) {
        tenantSupport.applyTenant(em);
        if (pedidos == null || pedidos.isEmpty()) return new CotizacionVentaLocal(List.of(), 0L);
        List<ItemResuelto> resueltos = resolverItems(pedidos);
        List<ItemCotizado> items = resueltos.stream().map(item -> new ItemCotizado(
                item.prdId(), item.varId(), item.cantidad(), item.precioCentavos() / 100L,
                item.precioCentavos() * item.cantidad() / 100L, item.nombre())).toList();
        long total = items.stream().mapToLong(ItemCotizado::subtotal).sum();
        return new CotizacionVentaLocal(items, total);
    }

    @Transactional
    public VentaLocalCreada crear(Long tndId, Long adminId, VentaLocalRequest req) {
        tenantSupport.applyTenant(em);

        if (req.items() == null || req.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agrega al menos un producto a la venta");
        }
        if (req.metodoPago() == null || req.metodoPago().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona un método de pago");
        }

        Long usrId = resolverCliente(tndId, req);
        List<ItemResuelto> items = resolverItems(req.items());

        long total = items.stream().mapToLong(i -> i.precioCentavos() * i.cantidad()).sum();
        if (total <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El total de la venta debe ser mayor a $0");
        }
        String numero = generarNumeroUnico();

        Number pedIdNum = (Number) em.createNativeQuery("""
                INSERT INTO pedidos (ped_tnd_id, ped_usr_id, ped_numero, ped_estado,
                                      ped_subtotal_centavos, ped_total_centavos, ped_notas)
                VALUES (:tndId, :usrId, :numero, CAST('pagado' AS estado_pedido), :subtotal, :total, :notas)
                RETURNING ped_id
                """)
                .setParameter("tndId", tndId)
                .setParameter("usrId", usrId)
                .setParameter("numero", numero)
                .setParameter("subtotal", total)
                .setParameter("total", total)
                .setParameter("notas", (req.notas() != null && !req.notas().isBlank()) ? req.notas().trim() : null)
                .getSingleResult();
        Long pedId = pedIdNum.longValue();

        for (ItemResuelto item : items) {
            em.createNativeQuery("""
                    INSERT INTO pedido_items (pi_ped_id, pi_prd_id, pi_var_id, pi_nombre_snap, pi_imagen_snap,
                                               pi_precio_unitario_centavos, pi_cantidad, pi_subtotal_centavos)
                    VALUES (:pedId, :prdId, :varId, :nombre, :imagen, :precio, :cantidad, :subtotal)
                    """)
                    .setParameter("pedId", pedId)
                    .setParameter("prdId", item.prdId())
                    .setParameter("varId", item.varId())
                    .setParameter("nombre", item.nombre())
                    .setParameter("imagen", item.imagen())
                    .setParameter("precio", item.precioCentavos())
                    .setParameter("cantidad", item.cantidad())
                    .setParameter("subtotal", item.precioCentavos() * item.cantidad())
                    .executeUpdate();

            if (item.varId() != null) {
                int updated = em.createNativeQuery("""
                        UPDATE variantes SET var_stock = var_stock - :cantidad
                        WHERE var_id = :varId AND var_stock >= :cantidad
                        """)
                        .setParameter("cantidad", item.cantidad())
                        .setParameter("varId", item.varId())
                        .executeUpdate();
                if (updated == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "No hay suficiente stock de \"" + item.nombre() + "\"");
                }
            }
        }

        em.createNativeQuery("""
                INSERT INTO pagos (pag_ped_id, pag_usr_id, pag_referencia, pag_proveedor, pag_metodo,
                                    pag_estado, pag_monto_centavos)
                VALUES (:pedId, :usrId, :referencia, CAST('MANUAL' AS proveedor_pago),
                        CAST(:metodo AS metodo_pago), CAST('APPROVED' AS estado_pago), :monto)
                """)
                .setParameter("pedId", pedId)
                .setParameter("usrId", usrId)
                .setParameter("referencia", "LOCAL-" + numero)
                .setParameter("metodo", req.metodoPago())
                .setParameter("monto", total)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO pedido_historial_estados (phe_ped_id, phe_estado, phe_admin_id)
                VALUES (:pedId, CAST('pagado' AS estado_pedido), :adminId)
                """)
                .setParameter("pedId", pedId)
                .setParameter("adminId", adminId)
                .executeUpdate();

        auditoriaService.registrar(tndId, adminId, "venta_local.creada", "pedido", pedId,
                Map.of("numero", numero, "total", total / 100L, "usr_id", usrId));

        log.info("[VentaLocal] Pedido {} ({}) creado por admin {} — cliente usrId={} total={}",
                pedId, numero, adminId, usrId, total);

        return new VentaLocalCreada(pedId, numero);
    }

    // ── Cliente ──────────────────────────────────────────────────────────────

    private Long resolverCliente(Long tndId, VentaLocalRequest req) {
        if (req.usrId() != null) {
            Number count = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM usuarios WHERE usr_id = :id AND usr_tnd_id = :tndId")
                    .setParameter("id", req.usrId())
                    .setParameter("tndId", tndId)
                    .getSingleResult();
            if (count.longValue() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cliente no encontrado en esta tienda");
            }
            return req.usrId();
        }

        if (req.nombre() == null || req.nombre().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el nombre del cliente");
        }
        if (req.numeroDocumento() == null || req.numeroDocumento().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el número de documento del cliente");
        }
        String tipoDocumento = (req.tipoDocumento() == null || req.tipoDocumento().isBlank())
                ? "CC" : req.tipoDocumento().trim().toUpperCase();
        String numeroDocumento = req.numeroDocumento().trim();

        @SuppressWarnings("unchecked")
        List<Number> existentes = em.createNativeQuery("""
                SELECT cp.cp_usr_id FROM clientes_perfil cp
                WHERE cp.cp_tnd_id = :tndId AND cp.cp_tipo_documento = CAST(:tipoDocumento AS tipo_documento)
                  AND cp.cp_numero_documento = :numeroDocumento
                """)
                .setParameter("tndId", tndId)
                .setParameter("tipoDocumento", tipoDocumento)
                .setParameter("numeroDocumento", numeroDocumento)
                .getResultList();

        if (!existentes.isEmpty()) {
            return existentes.get(0).longValue();
        }

        // Cliente de mostrador: fila en usuarios sin credenciales de acceso (provider LOCAL,
        // sin email). Si esta misma cédula se registra después en la tienda online, esa
        // misma fila se asciende a cuenta real — ver UsuarioAuthService.ascenderOCrear.
        Number usrIdNum = (Number) em.createNativeQuery("""
                INSERT INTO usuarios (usr_provider, usr_tnd_id)
                VALUES (CAST('LOCAL' AS auth_provider), :tndId)
                RETURNING usr_id
                """)
                .setParameter("tndId", tndId)
                .getSingleResult();
        Long usrId = usrIdNum.longValue();

        em.createNativeQuery("""
                INSERT INTO clientes_perfil (cp_usr_id, cp_tnd_id, cp_nombre, cp_tipo_documento, cp_numero_documento)
                VALUES (:usrId, :tndId, :nombre, CAST(:tipoDocumento AS tipo_documento), :numeroDocumento)
                """)
                .setParameter("usrId", usrId)
                .setParameter("tndId", tndId)
                .setParameter("nombre", req.nombre().trim())
                .setParameter("tipoDocumento", tipoDocumento)
                .setParameter("numeroDocumento", numeroDocumento)
                .executeUpdate();

        return usrId;
    }

    // ── Productos/variantes ──────────────────────────────────────────────────

    private List<ItemResuelto> resolverItems(List<VentaLocalRequest.ItemVentaLocal> pedidos) {
        List<ItemResuelto> resueltos = new ArrayList<>();
        for (VentaLocalRequest.ItemVentaLocal item : pedidos) {
            if (item.prdId() == null || item.cantidad() == null || item.cantidad() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ítem de venta inválido");
            }

            Object[] row;
            try {
                row = (Object[]) em.createNativeQuery("""
                        SELECT p.prd_nombre, p.prd_precio_centavos, p.prd_precio_descuento_centavos,
                               p.prd_oferta_hasta, p.prd_activo,
                               COALESCE(v.var_stock,
                                   (SELECT COALESCE(SUM(var_stock), 0) FROM variantes
                                     WHERE var_prd_id = p.prd_id AND var_activo = true)) AS stock,
                               COALESCE(
                                   (SELECT pi_url FROM producto_imagenes
                                     WHERE pi_prd_id = p.prd_id AND pi_tipo = 'imagen' AND pi_var_id = :varId
                                     ORDER BY pi_orden ASC LIMIT 1),
                                   (SELECT pi_url FROM producto_imagenes
                                     WHERE pi_prd_id = p.prd_id AND pi_tipo = 'imagen'
                                     ORDER BY pi_orden ASC LIMIT 1)
                               ) AS imagen
                        FROM productos p
                        LEFT JOIN variantes v ON v.var_id = :varId
                        WHERE p.prd_id = :prdId
                        """)
                        .setParameter("prdId", item.prdId())
                        .setParameter("varId", item.varId())
                        .getSingleResult();
            } catch (NoResultException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Producto no encontrado");
            }

            String nombre = (String) row[0];
            long precioBase = ((Number) row[1]).longValue();
            Long precioDescuento = row[2] != null ? ((Number) row[2]).longValue() : null;
            OffsetDateTime ofertaHasta = row[3] instanceof OffsetDateTime odt ? odt : null;
            boolean activo = Boolean.TRUE.equals(row[4]);
            int stock = ((Number) row[5]).intValue();
            String imagen = (String) row[6];

            if (!activo) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El producto \"" + nombre + "\" no está activo");
            }
            if (stock < item.cantidad()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No hay suficiente stock de \"" + nombre + "\" (disponible: " + stock + ")");
            }

            boolean ofertaVigente = precioDescuento != null && precioDescuento > 0
                    && (ofertaHasta == null || ofertaHasta.isAfter(OffsetDateTime.now()));
            long precioFinal = ofertaVigente ? precioDescuento : precioBase;

            resueltos.add(new ItemResuelto(item.prdId(), item.varId(), item.cantidad(), precioFinal, nombre, imagen));
        }
        return resueltos;
    }

    // ── Número de pedido (mismo patrón que PedidoCreacionService.generarNumeroUnico) ────────

    private String generarNumeroUnico() {
        for (int i = 0; i < 5; i++) {
            String candidato = "LOC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + String.format("%04d", RANDOM.nextInt(10_000));
            Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM pedidos WHERE ped_numero = :n")
                    .setParameter("n", candidato)
                    .getSingleResult();
            if (count.longValue() == 0) return candidato;
        }
        throw new IllegalStateException("No se pudo generar un número de pedido único");
    }
}
