package jaider.ecommerce.catalogo.producto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // Usada por el listado paginado del admin. Búsqueda por texto vía prd_tsv
    // (nombre + descripción) y marca (dentro del jsonb prd_ficha_tecnica), ya
    // que la marca no forma parte del tsvector generado por el trigger.
    @Query(
        value = """
            SELECT * FROM productos p
            WHERE (:catId IS NULL OR p.prd_cat_id = :catId)
              AND (:activo IS NULL OR p.prd_activo = :activo)
              AND (
                CAST(:q AS text) IS NULL
                OR p.prd_tsv @@ plainto_tsquery('spanish', :q)
                OR p.prd_ficha_tecnica ->> 'marca' ILIKE CONCAT('%', :q, '%')
                OR p.prd_slug ILIKE CONCAT('%', :q, '%')
              )
            ORDER BY p.prd_creado_en DESC
            """,
        countQuery = """
            SELECT COUNT(*) FROM productos p
            WHERE (:catId IS NULL OR p.prd_cat_id = :catId)
              AND (:activo IS NULL OR p.prd_activo = :activo)
              AND (
                CAST(:q AS text) IS NULL
                OR p.prd_tsv @@ plainto_tsquery('spanish', :q)
                OR p.prd_ficha_tecnica ->> 'marca' ILIKE CONCAT('%', :q, '%')
                OR p.prd_slug ILIKE CONCAT('%', :q, '%')
              )
            """,
        nativeQuery = true
    )
    Page<Producto> search(@Param("catId") Long catId, @Param("activo") Boolean activo,
                           @Param("q") String q, Pageable pageable);
}
