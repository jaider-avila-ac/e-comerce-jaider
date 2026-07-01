package jaider.ecommerce.catalogo.producto;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductoImagenRepository extends JpaRepository<ProductoImagen, Long> {
    List<ProductoImagen> findByPrdIdOrderByOrdenAscIdAsc(Long prdId);

    void deleteByPrdId(Long prdId);

    // Primera imagen de cualquier producto activo de una categoría (fallback para imagen de categoría)
    @Query(value = "SELECT pi.pi_url FROM producto_imagenes pi " +
                   "JOIN productos p ON p.prd_id = pi.pi_prd_id " +
                   "WHERE p.prd_cat_id = :catId AND p.prd_activo = true " +
                   "ORDER BY p.prd_id ASC, pi.pi_orden ASC LIMIT 1",
           nativeQuery = true)
    Optional<String> findFirstImageUrlByCatId(@Param("catId") Long catId);
}
