package jaider.ecommerce.tienda;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TiendaRepository extends JpaRepository<Tienda, Long> {
    Optional<Tienda> findBySlug(String slug);
    boolean existsBySecretAlias(String secretAlias);
    boolean existsByNit(String nit);
    // §3.3 del plan: "tenant inexistente o inactivo: rechazar la operación" — ver TenantEstadoCache,
    // el único punto que usa esto (TenantInterceptor, una vez por solicitud).
    boolean existsByIdAndActivoTrue(Long id);
}
