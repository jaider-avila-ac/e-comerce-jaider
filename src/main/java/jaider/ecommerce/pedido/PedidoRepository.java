package jaider.ecommerce.pedido;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

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

    // Corrección de auditoría (2026-09-01) — reserva atómica antes de llamar a Envia: la
    // comprobación anterior (leer el pedido, ver que shipmentId es null, y RECIÉN AHÍ cobrar)
    // dejaba una ventana real donde dos solicitudes concurrentes ("generar guía" con doble clic,
    // o dos pestañas del admin) podían pasar la validación antes de que cualquiera escribiera
    // nada, generando y cobrando DOS guías reales por el mismo pedido. Este UPDATE con
    // WHERE ... IS NULL es atómico a nivel de Postgres: si dos transacciones lo intentan a la
    // vez, la fila queda bloqueada para la segunda hasta que la primera termine, y para ese
    // momento el WHERE ya no matchea nada (la primera ya escribió un valor no nulo) — la segunda
    // recibe 0 filas afectadas y nunca llama a Envia. 'RESERVANDO' es un valor temporal: si la
    // llamada a Envia falla después, liberarReservaGuia() lo limpia para permitir reintentar.
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE pedidos SET ped_envia_shipment_id = 'RESERVANDO' WHERE ped_id = :id AND ped_envia_shipment_id IS NULL",
           nativeQuery = true)
    int reservarParaGuiaEnvia(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE pedidos SET ped_envia_shipment_id = NULL WHERE ped_id = :id AND ped_envia_shipment_id = 'RESERVANDO'",
           nativeQuery = true)
    void liberarReservaGuiaEnvia(@Param("id") Long id);

    // Corrección de auditoría (2026-09-01, tercera vuelta): reemplaza 'RESERVANDO' por el
    // shipmentId REAL apenas Envia lo confirma — antes de intentar guardar el resto de los
    // datos descriptivos (transportadora/tracking/PDF/costo). Es la escritura MÍNIMA que importa:
    // una vez que esta UPDATE hace commit (en su propia transacción, ver
    // EnvioGuiaTransaccionesService), el pedido nunca más puede volver a pasar el WHERE ...
    // IS NULL de reservarParaGuiaEnvia, así que ya no puede generarse una segunda guía real
    // aunque el resto del registro (registrarGuiaEnvia) falle después. Antes, las tres escrituras
    // (reservar, llamar a Envia, registrar) vivían en la MISMA transacción — si el commit final
    // fallaba, Postgres revertía también la reserva, dejando el pedido como si nunca se hubiera
    // generado nada aunque Envia ya hubiera cobrado de verdad.
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE pedidos SET ped_envia_shipment_id = :shipmentId WHERE ped_id = :id AND ped_envia_shipment_id = 'RESERVANDO'",
           nativeQuery = true)
    int confirmarShipmentIdGuiaEnvia(@Param("id") Long id, @Param("shipmentId") String shipmentId);

    // PLAN_INTEGRACION_ENVIA.md, Fase 4 — igual que updateSeguimiento, pero además de las
    // columnas de seguimiento ya existentes guarda las 3 nuevas de la guía real generada con
    // Envia. UPDATE explícito (no repo.save()) por la misma razón que el resto de este
    // repositorio: repo.save() reescribe TODAS las columnas, incluida ped_estado, que Postgres
    // no deja bindear como varchar sin CAST (es un enum nativo) — cualquier UPDATE de esta tabla
    // pasa por consultas nativas explícitas, nunca por una entidad completa.
    // Solo describe la guía (carrier/tracking/PDF/costo, más el ambiente usado — corrección de
    // auditoría, ver ped_envia_ambiente) — el shipmentId YA quedó persistido de forma durable por
    // confirmarShipmentIdGuiaEnvia() antes de llegar acá, así que el WHERE exige que siga siendo
    // el mismo (defensa extra; con la reserva atómica esto nunca debería fallar en la práctica).
    // Si ESTA escritura falla, el pedido sigue teniendo su shipmentId real (bloqueando cualquier
    // guía duplicada) aunque falten los campos descriptivos — reconciliable a mano con ese id.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE pedidos SET ped_transportadora = :transportadora, ped_codigo_rastreo = :codigo,
                                ped_link_seguimiento = :link, ped_mostrar_seguimiento = :mostrar,
                                ped_envia_guia_url = :guiaUrl, ped_envia_costo_real_centavos = :costoRealCentavos,
                                ped_envia_ambiente = :ambiente
            WHERE ped_id = :id AND ped_envia_shipment_id = :shipmentId
            """, nativeQuery = true)
    int registrarGuiaEnvia(@Param("id") Long id, @Param("transportadora") String transportadora,
                            @Param("codigo") String codigo, @Param("link") String link,
                            @Param("mostrar") String mostrar, @Param("shipmentId") String shipmentId,
                            @Param("guiaUrl") String guiaUrl, @Param("costoRealCentavos") Long costoRealCentavos,
                            @Param("ambiente") String ambiente);

    // PLAN_INTEGRACION_ENVIA.md, Fase 5 — webhook de Envia. A diferencia de updateEstadoSi (que
    // exige conocer el estado ANTERIOR exacto porque el llamador es un admin que ya leyó el
    // pedido), acá no hace falta: un webhook puede llegar más de una vez (reintentos normales de
    // cualquier webhook) o fuera de orden, así que la condición es "todavía no está en un estado
    // final" en vez de "está exactamente en tal estado" — deliberadamente simple e idempotente,
    // no reemplaza el flujo de PedidoService (colaborador asignado, etc.), que sigue siendo el
    // único camino para las transiciones que decide el propio staff (preparando/enviado).
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE pedidos SET ped_estado = CAST(:estadoNuevo AS estado_pedido)
            WHERE ped_id = :id
              AND ped_estado NOT IN (CAST('entregado' AS estado_pedido), CAST('cancelado' AS estado_pedido), CAST('devuelto' AS estado_pedido))
            """, nativeQuery = true)
    int avanzarEstadoPorWebhookEnvia(@Param("id") Long id, @Param("estadoNuevo") String estadoNuevo);

    Optional<Pedido> findByTndIdAndCodigoRastreo(Long tndId, String codigoRastreo);

    Optional<Pedido> findByNumeroAndUsrId(String numero, Long usrId);

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
