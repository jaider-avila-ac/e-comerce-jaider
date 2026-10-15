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
    // Resolver tenant por api key (ver TiendaApiKeyService/TenantInterceptor) — tnd_api_key_hash
    // tiene índice único, así que esto es O(1), no un recorrido de toda la tabla.
    Optional<Tienda> findByApiKeyHashAndActivoTrue(String apiKeyHash);
}
