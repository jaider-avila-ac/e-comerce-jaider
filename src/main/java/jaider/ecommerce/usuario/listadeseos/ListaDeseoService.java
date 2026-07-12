package jaider.ecommerce.usuario.listadeseos;

import jaider.ecommerce.catalogo.publico.PublicCatalogFacade;
import jaider.ecommerce.catalogo.publico.PublicProductoResponse;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ListaDeseoService {

    private final TenantSupport tenantSupport;
    private final PublicCatalogFacade catalogFacade;

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<Long> listarIds(Long usrId) {
        tenantSupport.applyTenant(em);
        return em.createNativeQuery(
                "SELECT ld_prd_id FROM lista_deseos WHERE ld_usr_id = :usrId ORDER BY ld_creado_en DESC")
                .setParameter("usrId", usrId)
                .getResultList()
                .stream().map(r -> ((Number) r).longValue()).toList();
    }

    @Transactional(readOnly = true)
    public List<PublicProductoResponse> listarDetalle(Long usrId) {
        return listarIds(usrId).stream()
                .map(prdId -> {
                    try {
                        return catalogFacade.getProductoById(prdId);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(p -> p != null)
                .toList();
    }

    @Transactional
    public void agregar(Long usrId, Long prdId) {
        tenantSupport.applyTenant(em);
        em.createNativeQuery("""
                INSERT INTO lista_deseos (ld_usr_id, ld_prd_id)
                VALUES (:usrId, :prdId)
                ON CONFLICT (ld_usr_id, ld_prd_id) DO NOTHING
                """)
                .setParameter("usrId", usrId)
                .setParameter("prdId", prdId)
                .executeUpdate();
    }

    @Transactional
    public void quitar(Long usrId, Long prdId) {
        tenantSupport.applyTenant(em);
        em.createNativeQuery("DELETE FROM lista_deseos WHERE ld_usr_id = :usrId AND ld_prd_id = :prdId")
                .setParameter("usrId", usrId)
                .setParameter("prdId", prdId)
                .executeUpdate();
    }
}
