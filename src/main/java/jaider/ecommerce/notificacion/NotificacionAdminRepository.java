package jaider.ecommerce.notificacion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificacionAdminRepository extends JpaRepository<NotificacionAdmin, Long> {

    // Sin filtro por tnd_id explícito: RLS (pol_notificaciones_admin) ya restringe
    // las filas visibles a la tienda actual — mismo patrón que PedidoRepository.
    List<NotificacionAdmin> findTop50ByOrderByCreadoEnDesc();

    @Modifying
    @Query("UPDATE NotificacionAdmin n SET n.leidaEn = CURRENT_TIMESTAMP WHERE n.id = :id AND n.leidaEn IS NULL")
    int marcarLeida(@Param("id") Long id);

    @Modifying
    @Query("UPDATE NotificacionAdmin n SET n.leidaEn = CURRENT_TIMESTAMP WHERE n.leidaEn IS NULL")
    int marcarTodasLeidas();
}
