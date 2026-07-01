package jaider.ecommerce.tienda;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TiendaRepository extends JpaRepository<Tienda, Long> {
    Optional<Tienda> findBySlug(String slug);
}
