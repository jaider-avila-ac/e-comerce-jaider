package jaider.ecommerce.pago;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PagoRepository extends JpaRepository<Pago, Long> {

    Optional<Pago> findByReferencia(String referencia);

    List<Pago> findByPedIdOrderByIdDesc(Long pedId);
}
