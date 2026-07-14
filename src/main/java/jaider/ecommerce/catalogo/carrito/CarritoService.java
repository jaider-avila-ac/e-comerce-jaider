package jaider.ecommerce.catalogo.carrito;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CarritoService {

    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Value("${app.shipping.flat-cost-centavos}")
    private long shippingFlatCostCentavos;

    @Transactional
    public Map<String, Object> getCarrito(Long usrId, Long tndId) {
        tenantSupport.applyTenant(em);
        Long carId = ensureCarrito(usrId);
        return buildCarrito(carId, tndId);
    }

    @Transactional
    public Map<String, Object> addItem(Long usrId, Long tndId, Long prdId, String talla, String color, int cantidad) {
        tenantSupport.applyTenant(em);
        Long carId = ensureCarrito(usrId);
        Long varId = resolveVarId(prdId, talla, color);

        Object[] prd;
        try {
            prd = (Object[]) em.createNativeQuery("""
                SELECT COALESCE(prd_precio_descuento_centavos, prd_precio_centavos), prd_activo
                FROM productos WHERE prd_id = :prdId
                """)
                .setParameter("prdId", prdId)
                .getSingleResult();
        } catch (NoResultException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado");
        }
        if (!Boolean.TRUE.equals(prd[1])) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Producto no disponible");
        }
        long precioCentavos = ((Number) prd[0]).longValue();

        em.createNativeQuery("""
            INSERT INTO carrito_items (ci_car_id, ci_prd_id, ci_var_id, ci_cantidad, ci_precio_snap_centavos)
            VALUES (:carId, :prdId, :varId, :cantidad, :precio)
            ON CONFLICT (ci_car_id, ci_prd_id, COALESCE(ci_var_id, 0::bigint)) DO UPDATE SET
                ci_cantidad = carrito_items.ci_cantidad + EXCLUDED.ci_cantidad,
                ci_precio_snap_centavos = EXCLUDED.ci_precio_snap_centavos
            """)
            .setParameter("carId", carId)
            .setParameter("prdId", prdId)
            .setParameter("varId", varId)
            .setParameter("cantidad", cantidad)
            .setParameter("precio", precioCentavos)
            .executeUpdate();

        return buildCarrito(carId, tndId);
    }

    @Transactional
    public Map<String, Object> updateItem(Long usrId, Long tndId, Long itemId, int cantidad) {
        tenantSupport.applyTenant(em);
        Long carId = ensureCarrito(usrId);

        if (cantidad <= 0) {
            em.createNativeQuery("DELETE FROM carrito_items WHERE ci_id = :id AND ci_car_id = :carId")
                .setParameter("id", itemId)
                .setParameter("carId", carId)
                .executeUpdate();
        } else {
            int updated = em.createNativeQuery("""
                UPDATE carrito_items SET ci_cantidad = :cantidad
                WHERE ci_id = :id AND ci_car_id = :carId
                """)
                .setParameter("cantidad", cantidad)
                .setParameter("id", itemId)
                .setParameter("carId", carId)
                .executeUpdate();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ítem no encontrado");
            }
        }
        return buildCarrito(carId, tndId);
    }

    @Transactional
    public Map<String, Object> removeItem(Long usrId, Long tndId, Long itemId) {
        tenantSupport.applyTenant(em);
        Long carId = ensureCarrito(usrId);

        int deleted = em.createNativeQuery("DELETE FROM carrito_items WHERE ci_id = :id AND ci_car_id = :carId")
            .setParameter("id", itemId)
            .setParameter("carId", carId)
            .executeUpdate();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ítem no encontrado");
        }
        return buildCarrito(carId, tndId);
    }

    @Transactional
    public Map<String, Object> clear(Long usrId, Long tndId) {
        tenantSupport.applyTenant(em);
        Long carId = ensureCarrito(usrId);
        em.createNativeQuery("DELETE FROM carrito_items WHERE ci_car_id = :carId")
            .setParameter("carId", carId)
            .executeUpdate();
        return buildCarrito(carId, tndId);
    }

    private Long ensureCarrito(Long usrId) {
        em.createNativeQuery("""
            INSERT INTO carritos (car_usr_id) VALUES (:usrId)
            ON CONFLICT (car_usr_id) DO NOTHING
            """)
            .setParameter("usrId", usrId)
            .executeUpdate();

        return ((Number) em.createNativeQuery("SELECT car_id FROM carritos WHERE car_usr_id = :usrId")
            .setParameter("usrId", usrId)
            .getSingleResult()).longValue();
    }

    private Long resolveVarId(Long prdId, String talla, String color) {
        boolean sinTalla = talla == null || talla.isBlank();
        boolean sinColor = color == null || color.isBlank();
        if (sinTalla && sinColor) return null;

        StringBuilder sql = new StringBuilder(
            "SELECT var_id FROM variantes WHERE var_prd_id = :prdId AND var_activo = true");
        if (!sinTalla) sql.append(" AND var_talla = :talla");
        if (!sinColor) sql.append(" AND var_color = :color");
        sql.append(" ORDER BY var_id ASC LIMIT 1");

        var query = em.createNativeQuery(sql.toString()).setParameter("prdId", prdId);
        if (!sinTalla) query.setParameter("talla", talla);
        if (!sinColor) query.setParameter("color", color);

        List<?> rows = query.getResultList();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La variante seleccionada no existe");
        }
        return ((Number) rows.get(0)).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCarrito(Long carId, Long tndId) {
        List<Object[]> rows = em.createNativeQuery("""
            SELECT ci.ci_id, ci.ci_prd_id, ci.ci_cantidad, ci.ci_precio_snap_centavos,
                   p.prd_nombre, p.prd_activo, p.prd_slug,
                   v.var_talla, v.var_color,
                   p.prd_ficha_tecnica ->> 'marca' AS marca,
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
            JOIN productos p ON p.prd_id = ci.ci_prd_id
            LEFT JOIN variantes v ON v.var_id = ci.ci_var_id
            WHERE ci.ci_car_id = :carId
            ORDER BY ci.ci_agregado_en ASC
            """)
            .setParameter("carId", carId)
            .getResultList();

        List<Map<String, Object>> items = new ArrayList<>();
        long total = 0;
        int count = 0;
        for (Object[] r : rows) {
            long precioCentavos = ((Number) r[3]).longValue();
            long precio = precioCentavos / 100L;
            int cantidad = ((Number) r[2]).intValue();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ((Number) r[0]).longValue());
            item.put("producto_id", ((Number) r[1]).longValue());
            item.put("cantidad", cantidad);
            item.put("precio", precio);
            item.put("subtotal", precio * cantidad);
            item.put("nombre", r[4]);
            item.put("producto_activo", r[5]);
            item.put("slug", r[6]);
            item.put("talla", r[7]);
            item.put("color", r[8]);
            item.put("marca", r[9]);
            item.put("imagen", r[10]);
            item.put("stock_disponible", r[11] != null ? ((Number) r[11]).intValue() : 0);
            items.add(item);

            total += precio * cantidad;
            count += cantidad;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("count", count);
        result.put("total", total);
        Object[] envioConfig = (Object[]) em.createNativeQuery("""
                SELECT tnd_envio_gratis_activo, tnd_envio_gratis_desde_centavos
                FROM tiendas WHERE tnd_id = :tndId
                """)
                .setParameter("tndId", tndId)
                .getSingleResult();
        boolean envioGratisActivo = Boolean.TRUE.equals(envioConfig[0]);
        long envioGratisDesde = ((Number) envioConfig[1]).longValue() / 100L;
        long envio = envioGratisActivo && total >= envioGratisDesde ? 0L : shippingFlatCostCentavos / 100L;
        long faltanteEnvioGratis = envioGratisActivo ? Math.max(0L, envioGratisDesde - total) : 0L;
        int progresoEnvioGratis = envioGratisActivo && envioGratisDesde > 0
                ? (int) Math.min(100L, total * 100L / envioGratisDesde)
                : 0;
        result.put("envio", envio);
        result.put("total_con_envio", total + envio);
        result.put("envio_gratis_activo", envioGratisActivo);
        result.put("envio_gratis_desde", envioGratisDesde);
        result.put("envio_gratis_alcanzado", envioGratisActivo && total >= envioGratisDesde);
        result.put("faltante_envio_gratis", faltanteEnvioGratis);
        result.put("progreso_envio_gratis", progresoEnvioGratis);
        return result;
    }

    @Transactional(readOnly = true)
    public List<ValidarItemResult> validar(List<ValidarCarritoRequest.Item> items) {
        tenantSupport.applyTenant(em);

        List<ValidarItemResult> result = new ArrayList<>();

        for (ValidarCarritoRequest.Item item : items) {
            // Verificar si el producto existe y está activo (RLS filtra por tenant)
            @SuppressWarnings("unchecked")
            List<Object> prdRows = em.createNativeQuery(
                    "SELECT prd_activo FROM productos WHERE prd_id = :id"
            ).setParameter("id", item.productId()).getResultList();
            Boolean productoActivo = prdRows.isEmpty() ? null : (Boolean) prdRows.get(0);

            if (productoActivo == null) {
                // Producto eliminado o no pertenece a este tenant
                result.add(new ValidarItemResult(item.productId(), item.talla(), item.color(), 0, false));
                continue;
            }

            // Stock disponible para la combinación talla+color solicitada
            // Construir query dinámicamente para evitar el problema de NULL sin tipo en PostgreSQL
            StringBuilder sql = new StringBuilder(
                    "SELECT COALESCE(SUM(var_stock), 0) FROM variantes " +
                    "WHERE var_prd_id = :prdId AND var_activo = true"
            );
            if (item.talla() != null) sql.append(" AND var_talla = :talla");
            if (item.color() != null) sql.append(" AND var_color = :color");

            var stockQuery = em.createNativeQuery(sql.toString())
                    .setParameter("prdId", item.productId());
            if (item.talla() != null) stockQuery.setParameter("talla", item.talla());
            if (item.color() != null) stockQuery.setParameter("color", item.color());

            Number stock = (Number) stockQuery.getSingleResult();

            result.add(new ValidarItemResult(
                    item.productId(),
                    item.talla(),
                    item.color(),
                    stock != null ? stock.intValue() : 0,
                    productoActivo
            ));
        }

        return result;
    }
}
