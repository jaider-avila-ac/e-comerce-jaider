package jaider.ecommerce.tienda.banner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BannerRepository extends JpaRepository<Banner, Long> {

    List<Banner> findAllByOrderByPosicionAscOrdenAscIdAsc();

    @Query(value = "SELECT * FROM banners WHERE ban_posicion = CAST(:posicion AS posicion_banner) AND ban_activo = true ORDER BY ban_orden, ban_id", nativeQuery = true)
    List<Banner> findByPosicionActivos(@Param("posicion") String posicion);

    @Modifying
    @Query(value = "UPDATE banners SET ban_posicion = CAST(:posicion AS posicion_banner), " +
            "ban_tipo = CAST(:tipo AS tipo_media) WHERE ban_id = :id", nativeQuery = true)
    void updatePosicionTipo(@Param("id") Long id, @Param("posicion") String posicion, @Param("tipo") String tipo);
}
