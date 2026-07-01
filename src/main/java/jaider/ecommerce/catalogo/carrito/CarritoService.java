package jaider.ecommerce.catalogo.carrito;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CarritoService {

    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

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
