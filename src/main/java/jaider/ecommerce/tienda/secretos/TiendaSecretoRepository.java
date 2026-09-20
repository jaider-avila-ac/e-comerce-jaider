package jaider.ecommerce.tienda.secretos;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TiendaSecretoRepository extends JpaRepository<TiendaSecreto, Long> {
    Optional<TiendaSecreto> findByTndIdAndProveedorAndCampo(Long tndId, String proveedor, String campo);
    List<TiendaSecreto> findByTndIdAndProveedor(Long tndId, String proveedor);
}
