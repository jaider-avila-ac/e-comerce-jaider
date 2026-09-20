package jaider.ecommerce.tienda;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TiendaDominioRepository extends JpaRepository<TiendaDominio, Long> {
    Optional<TiendaDominio> findByDominioAndActivoTrue(String dominio);
    Optional<TiendaDominio> findByTndIdAndPrincipalTrueAndActivoTrue(Long tndId);
}
