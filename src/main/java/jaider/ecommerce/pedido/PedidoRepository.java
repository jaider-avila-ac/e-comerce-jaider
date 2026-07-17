package jaider.ecommerce.pedido;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    @Query("SELECT p FROM Pedido p WHERE p.estado <> 'pendiente_pago' ORDER BY p.creadoEn DESC")
    List<Pedido> findAllOrdered();

    @Query("SELECT p FROM Pedido p WHERE p.estado = :estado ORDER BY p.creadoEn DESC")
    List<Pedido> findByEstado(@Param("estado") String estado);

    // clearAutomatically = true: limpia el contexto JPA tras el UPDATE nativo,
    // evitando que Hibernate intente hacer flush de la entidad dirty antes del próximo SELECT.
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE pedidos SET ped_estado = CAST(:estado AS estado_pedido) WHERE ped_id = :id",
           nativeQuery = true)
    void updateEstado(@Param("id") Long id, @Param("estado") String estado);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE pedidos SET ped_transportadora = :transportadora, ped_codigo_rastreo = :codigo,
                                ped_link_seguimiento = :link, ped_mostrar_seguimiento = :mostrar
            WHERE ped_id = :id
            """, nativeQuery = true)
    void updateSeguimiento(@Param("id") Long id, @Param("transportadora") String transportadora,
                            @Param("codigo") String codigo, @Param("link") String link,
                            @Param("mostrar") String mostrar);

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
}
