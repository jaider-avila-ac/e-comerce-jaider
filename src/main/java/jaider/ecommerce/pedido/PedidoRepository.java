package jaider.ecommerce.pedido;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    @Query("SELECT p FROM Pedido p ORDER BY p.creadoEn DESC")
    List<Pedido> findAllOrdered();

    @Query("SELECT p FROM Pedido p WHERE p.estado = :estado ORDER BY p.creadoEn DESC")
    List<Pedido> findByEstado(@Param("estado") String estado);

    // clearAutomatically = true: limpia el contexto JPA tras el UPDATE nativo,
    // evitando que Hibernate intente hacer flush de la entidad dirty antes del próximo SELECT.
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE pedidos SET ped_estado = CAST(:estado AS estado_pedido) WHERE ped_id = :id",
           nativeQuery = true)
    void updateEstado(@Param("id") Long id, @Param("estado") String estado);
}
