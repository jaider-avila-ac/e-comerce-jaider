package jaider.ecommerce.pago.reembolso;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ReembolsoRepository extends JpaRepository<Reembolso, Long> {

    @Query("SELECT r FROM Reembolso r WHERE r.pedId = :pedId ORDER BY r.id DESC")
    List<Reembolso> findByPedIdOrderByIdDesc(@Param("pedId") Long pedId);

    // clearAutomatically = true: mismo motivo que en PedidoRepository/SolicitudDevolucionRepository
    // (evita que Hibernate reescriba ref_estado sin el CAST al hacer flush de la entidad managed).
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE reembolsos
            SET ref_estado = CAST(:estado AS estado_reembolso),
                ref_gateway_ref = COALESCE(:gatewayRef, ref_gateway_ref),
                ref_gateway_respuesta = CAST(:gatewayRespuesta AS jsonb),
                ref_error_mensaje = :errorMensaje,
                ref_confirmado_en = :confirmadoEn
            WHERE ref_id = :id
            """, nativeQuery = true)
    void actualizarProcesamiento(@Param("id") Long id, @Param("estado") String estado,
                                  @Param("gatewayRef") String gatewayRef,
                                  @Param("gatewayRespuesta") String gatewayRespuesta,
                                  @Param("errorMensaje") String errorMensaje,
                                  @Param("confirmadoEn") OffsetDateTime confirmadoEn);

    /** Compare-and-set: solo cierra el reembolso si TODAVÍA no está cerrado — dos admins
     *  confirmando el mismo reembolso manual casi a la vez no deben poder pisarse el estado final
     *  el uno al otro (CUARTA_AUDITORIA_FUNCIONAL_E_IDEMPOTENCIA.md §4, mismo patrón que
     *  PedidoRepository.updateEstadoSi / SolicitudDevolucionRepository.confirmarRecibida).
     *  @return filas afectadas; 0 = alguien más ya lo cerró primero. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE reembolsos
            SET ref_estado = CAST(:estado AS estado_reembolso), ref_error_mensaje = :nota, ref_confirmado_en = :confirmadoEn
            WHERE ref_id = :id AND ref_estado NOT IN (CAST('completado' AS estado_reembolso), CAST('rechazado' AS estado_reembolso))
            """, nativeQuery = true)
    int confirmarManual(@Param("id") Long id, @Param("estado") String estado, @Param("nota") String nota,
                         @Param("confirmadoEn") OffsetDateTime confirmadoEn);
}
