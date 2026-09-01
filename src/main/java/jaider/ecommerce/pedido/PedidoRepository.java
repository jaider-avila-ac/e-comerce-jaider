package jaider.ecommerce.pedido;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    @Query("""
            SELECT p FROM Pedido p WHERE p.estado <> 'pendiente_pago'
            AND (:colaboradorId IS NULL OR p.colaboradorId = :colaboradorId)
            AND (:sucursalId IS NULL OR p.sucursalId = :sucursalId)
            ORDER BY p.creadoEn DESC
            """)
    List<Pedido> findAllOrdered(@Param("colaboradorId") Long colaboradorId, @Param("sucursalId") Long sucursalId);

    @Query("""
            SELECT p FROM Pedido p WHERE p.estado = :estado
            AND (:colaboradorId IS NULL OR p.colaboradorId = :colaboradorId)
            AND (:sucursalId IS NULL OR p.sucursalId = :sucursalId)
            ORDER BY p.creadoEn DESC
            """)
    List<Pedido> findByEstado(@Param("estado") String estado, @Param("colaboradorId") Long colaboradorId,
                               @Param("sucursalId") Long sucursalId);

    // clearAutomatically = true: limpia el contexto JPA tras el UPDATE nativo,
    // evitando que Hibernate intente hacer flush de la entidad dirty antes del próximo SELECT.
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE pedidos SET ped_estado = CAST(:estado AS estado_pedido) WHERE ped_id = :id",
           nativeQuery = true)
    void updateEstado(@Param("id") Long id, @Param("estado") String estado);

    /** Compare-and-set real: solo cambia el estado si TODAVÍA está en el estado que el llamador
     *  leyó antes de decidir la transición — el UPDATE nunca "gana una carrera silenciosa". Ver
     *  PedidoService.aplicarTransicion(), que la usa en vez de updateEstado() precisamente para
     *  esto (riesgo de doble reembolso/doble historial con dos solicitudes concurrentes sobre el
     *  mismo pedido, señalado en las auditorías de idempotencia).
     *  @return filas afectadas — 0 significa que otra solicitud ya cambió el estado primero. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE pedidos SET ped_estado = CAST(:estadoNuevo AS estado_pedido)
            WHERE ped_id = :id AND ped_estado = CAST(:estadoEsperado AS estado_pedido)
            """, nativeQuery = true)
    int updateEstadoSi(@Param("id") Long id, @Param("estadoNuevo") String estadoNuevo,
                        @Param("estadoEsperado") String estadoEsperado);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE pedidos SET ped_transportadora = :transportadora, ped_codigo_rastreo = :codigo,
                                ped_link_seguimiento = :link, ped_mostrar_seguimiento = :mostrar
            WHERE ped_id = :id
            """, nativeQuery = true)
    void updateSeguimiento(@Param("id") Long id, @Param("transportadora") String transportadora,
                            @Param("codigo") String codigo, @Param("link") String link,
                            @Param("mostrar") String mostrar);

    // PLAN_INTEGRACION_ENVIA.md, Fase 4 — igual que updateSeguimiento, pero además de las
    // columnas de seguimiento ya existentes guarda las 3 nuevas de la guía real generada con
    // Envia. UPDATE explícito (no repo.save()) por la misma razón que el resto de este
    // repositorio: repo.save() reescribe TODAS las columnas, incluida ped_estado, que Postgres
    // no deja bindear como varchar sin CAST (es un enum nativo) — cualquier UPDATE de esta tabla
    // pasa por consultas nativas explícitas, nunca por una entidad completa.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE pedidos SET ped_transportadora = :transportadora, ped_codigo_rastreo = :codigo,
                                ped_link_seguimiento = :link, ped_mostrar_seguimiento = :mostrar,
                                ped_envia_shipment_id = :shipmentId, ped_envia_guia_url = :guiaUrl,
                                ped_envia_costo_real_centavos = :costoRealCentavos
            WHERE ped_id = :id
            """, nativeQuery = true)
    void registrarGuiaEnvia(@Param("id") Long id, @Param("transportadora") String transportadora,
                             @Param("codigo") String codigo, @Param("link") String link,
                             @Param("mostrar") String mostrar, @Param("shipmentId") String shipmentId,
                             @Param("guiaUrl") String guiaUrl, @Param("costoRealCentavos") Long costoRealCentavos);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE pedidos
            SET ped_cancel_motivo = :motivo, ped_cancel_motivo_otro = :motivoOtro, ped_cancel_nota = :nota,
                ped_cancelado_por = :adminId, ped_cancelado_en = :canceladoEn
            WHERE ped_id = :id
            """, nativeQuery = true)
    void registrarCancelacion(@Param("id") Long id, @Param("motivo") String motivo,
                               @Param("motivoOtro") String motivoOtro, @Param("nota") String nota,
                               @Param("adminId") Long adminId, @Param("canceladoEn") OffsetDateTime canceladoEn);

    // La tienda física del pedido se hereda del colaborador asignado — si se quita el
    // responsable (colaboradorId null), el subselect también da null y el pedido queda
    // sin tienda hasta que alguien más lo tome.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE pedidos SET ped_colaborador_id = :colaboradorId,
                                ped_sucursal_id = (SELECT sucursal_id FROM admin_users WHERE id = :colaboradorId)
            WHERE ped_id = :id
            """, nativeQuery = true)
    void asignarColaborador(@Param("id") Long id, @Param("colaboradorId") Long colaboradorId);
}
