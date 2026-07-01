package jaider.ecommerce.catalogo.coleccion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ColeccionRepository extends JpaRepository<Coleccion, Long> {

    List<Coleccion> findAllByOrderByOrdenAscNombreAsc();

    boolean existsBySlug(String slug);

    @Query(value = "SELECT prd_id FROM coleccion_productos WHERE col_id = :colId ORDER BY cp_orden ASC",
           nativeQuery = true)
    List<Long> findProductoIdsByColId(@Param("colId") Long colId);
}
