package jaider.ecommerce.tienda.envio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TiendaEmpaqueRepository extends JpaRepository<TiendaEmpaque, Long> {
    List<TiendaEmpaque> findAllByOrderByOrdenAscNombreAsc();
}
