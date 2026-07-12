package jaider.ecommerce.notificacion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificacionClienteRepository extends JpaRepository<NotificacionCliente, Long> {

    // El RLS de "notificaciones" solo aísla por tienda (join a usuarios.usr_tnd_id), no por
    // usr_id — dentro de la misma tienda hay muchos clientes, así que el filtro por usrId es
    // obligatorio aquí para no filtrar notificaciones de otros clientes de la misma tienda.
    @Query("SELECT n FROM NotificacionCliente n WHERE n.usrId = :usrId AND n.eliminadaEn IS NULL ORDER BY n.creadoEn DESC")
    List<NotificacionCliente> findActivasByUsrId(@Param("usrId") Long usrId);

    @Modifying
    @Query("UPDATE NotificacionCliente n SET n.leidaEn = CURRENT_TIMESTAMP WHERE n.id = :id AND n.usrId = :usrId AND n.leidaEn IS NULL")
    int marcarLeida(@Param("id") Long id, @Param("usrId") Long usrId);

    @Modifying
    @Query("UPDATE NotificacionCliente n SET n.leidaEn = CURRENT_TIMESTAMP WHERE n.usrId = :usrId AND n.leidaEn IS NULL")
    int marcarTodasLeidas(@Param("usrId") Long usrId);

    @Modifying
    @Query("UPDATE NotificacionCliente n SET n.eliminadaEn = CURRENT_TIMESTAMP WHERE n.id = :id AND n.usrId = :usrId")
    int eliminar(@Param("id") Long id, @Param("usrId") Long usrId);
}
