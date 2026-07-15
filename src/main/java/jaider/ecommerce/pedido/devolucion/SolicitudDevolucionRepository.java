package jaider.ecommerce.pedido.devolucion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SolicitudDevolucionRepository extends JpaRepository<SolicitudDevolucion, Long> {

    @Query("SELECT s FROM SolicitudDevolucion s WHERE s.estado = :estado ORDER BY s.creadoEn DESC")
    List<SolicitudDevolucion> findByEstado(@Param("estado") String estado);

    @Query("SELECT s FROM SolicitudDevolucion s ORDER BY s.creadoEn DESC")
    List<SolicitudDevolucion> findAllOrdered();

    @Query("SELECT s FROM SolicitudDevolucion s WHERE s.pedId = :pedId " +
           "AND s.estado NOT IN ('rechazada', 'cancelada') ORDER BY s.creadoEn DESC")
    Optional<SolicitudDevolucion> findActivaByPedId(@Param("pedId") Long pedId);

    @Query("SELECT s FROM SolicitudDevolucion s WHERE s.pedId = :pedId ORDER BY s.creadoEn DESC")
    List<SolicitudDevolucion> findByPedIdOrderByCreadoEnDesc(@Param("pedId") Long pedId);

    // clearAutomatically = true: mismo motivo que PedidoRepository.updateEstado (evita que
    // Hibernate reescriba sod_estado sin el CAST al hacer flush de la entidad managed).
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE solicitudes_devolucion SET sod_estado = CAST(:estado AS estado_devolucion) WHERE sod_id = :id",
           nativeQuery = true)
    void updateEstado(@Param("id") Long id, @Param("estado") String estado);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE solicitudes_devolucion
            SET sod_estado = CAST(:estado AS estado_devolucion), sod_dvd_id = :dvdId,
                sod_admin_nota = :adminNota, sod_revisado_en = :revisadoEn
            WHERE sod_id = :id
            """, nativeQuery = true)
    void aprobarORechazar(@Param("id") Long id, @Param("estado") String estado, @Param("dvdId") Long dvdId,
                          @Param("adminNota") String adminNota, @Param("revisadoEn") OffsetDateTime revisadoEn);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE solicitudes_devolucion
            SET sod_estado = CAST('en_transito' AS estado_devolucion), sod_codigo_rastreo = :codigo
            WHERE sod_id = :id
            """, nativeQuery = true)
    void registrarCodigoRastreo(@Param("id") Long id, @Param("codigo") String codigo);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE solicitudes_devolucion
            SET sod_estado = CAST('recibida' AS estado_devolucion), sod_recibida_en = :recibidaEn
            WHERE sod_id = :id
            """, nativeQuery = true)
    void confirmarRecibida(@Param("id") Long id, @Param("recibidaEn") OffsetDateTime recibidaEn);
}
