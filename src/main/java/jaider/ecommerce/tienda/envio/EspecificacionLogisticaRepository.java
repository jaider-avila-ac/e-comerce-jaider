package jaider.ecommerce.tienda.envio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EspecificacionLogisticaRepository extends JpaRepository<EspecificacionLogistica, Long> {
    List<EspecificacionLogistica> findAllByOrderByNombreAsc();
}
