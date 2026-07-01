package jaider.ecommerce.catalogo.producto;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    @Query("SELECT p FROM Producto p ORDER BY p.creadoEn DESC")
    List<Producto> findAllOrdered();

    @Query("SELECT p FROM Producto p WHERE p.catId = :catId ORDER BY p.nombre ASC")
    List<Producto> findByCatId(@Param("catId") Long catId);

    @Query("SELECT p FROM Producto p WHERE p.activo = true ORDER BY p.creadoEn DESC")
    List<Producto> findActivos();
}
