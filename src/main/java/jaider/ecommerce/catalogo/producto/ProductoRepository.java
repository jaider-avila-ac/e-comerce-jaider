package jaider.ecommerce.catalogo.producto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    @Query("SELECT p FROM Producto p ORDER BY p.creadoEn DESC")
    List<Producto> findAllOrdered();

    // Detalle público por slug (URLs "bonitas" /producto/nombre-del-producto en vez de
    // /producto/{id}) — uidx_prd_slug garantiza que sea único por tenant.
    Optional<Producto> findBySlug(String slug);

    // Solo la usa el catálogo PÚBLICO (PublicCatalogService.getProductos) — por eso filtra
    // p.activo=true directo en la query, a diferencia de search()/findAllOrdered(), que también
    // usa el admin y necesita poder ver productos inactivos.
    @Query("SELECT p FROM Producto p WHERE p.catId = :catId AND p.activo = true ORDER BY p.nombre ASC")
    List<Producto> findByCatId(@Param("catId") Long catId);

    @Query("SELECT p FROM Producto p WHERE p.activo = true ORDER BY p.creadoEn DESC")
    List<Producto> findActivos();

    // Usada por el listado paginado del admin. Búsqueda por texto vía prd_tsv
    // (nombre + descripción) y marca (dentro del jsonb prd_ficha_tecnica), ya
    // que la marca no forma parte del tsvector generado por el trigger.
    //
    // exigeEmpaqueValido: SOLO lo usa el catálogo PÚBLICO (PublicCatalogService), nunca el admin
    // — el admin necesita seguir viendo los productos sin empaque para poder corregirlos. Cuando
    // la tienda tiene envio_modo='envia' activo, un producto sin empaque activo asignado haría
    // fallar el cálculo real del envío (PaqueteCalculoService) si un cliente lo compra — se
    // excluye del catálogo público como red de seguridad, aunque en el flujo normal esto no
    // debería pasar (activar 'envia' ya exige que todo producto activo tenga empaque, ver
    // TiendaConfigService.validarListaParaEnvia; esto cubre un producto creado DESPUÉS de activar).
    @Query(
        value = """
            SELECT p.* FROM productos p
            LEFT JOIN tienda_empaques te ON te.tep_id = p.prd_empaque_id
            WHERE (:catId IS NULL OR p.prd_cat_id = :catId)
              AND (:activo IS NULL OR p.prd_activo = :activo)
              AND (
                CAST(:q AS text) IS NULL
                OR p.prd_tsv @@ plainto_tsquery('spanish', :q)
                OR p.prd_ficha_tecnica ->> 'marca' ILIKE CONCAT('%', :q, '%')
                OR p.prd_slug ILIKE CONCAT('%', :q, '%')
              )
              AND (:exigeEmpaqueValido = false OR (p.prd_empaque_id IS NOT NULL AND te.tep_activo = true))
            ORDER BY p.prd_creado_en DESC
            """,
        countQuery = """
            SELECT COUNT(*) FROM productos p
            LEFT JOIN tienda_empaques te ON te.tep_id = p.prd_empaque_id
            WHERE (:catId IS NULL OR p.prd_cat_id = :catId)
              AND (:activo IS NULL OR p.prd_activo = :activo)
              AND (
                CAST(:q AS text) IS NULL
                OR p.prd_tsv @@ plainto_tsquery('spanish', :q)
                OR p.prd_ficha_tecnica ->> 'marca' ILIKE CONCAT('%', :q, '%')
                OR p.prd_slug ILIKE CONCAT('%', :q, '%')
              )
              AND (:exigeEmpaqueValido = false OR (p.prd_empaque_id IS NOT NULL AND te.tep_activo = true))
            """,
        nativeQuery = true
    )
    Page<Producto> search(@Param("catId") Long catId, @Param("activo") Boolean activo,
                           @Param("q") String q, @Param("exigeEmpaqueValido") boolean exigeEmpaqueValido,
                           Pageable pageable);
}
