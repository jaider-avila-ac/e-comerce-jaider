package jaider.ecommerce.tienda.envio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TiendaEmpaqueRepository extends JpaRepository<TiendaEmpaque, Long> {
    List<TiendaEmpaque> findAllByOrderByOrdenAscNombreAsc();

    // Usado por PaqueteCalculoService — pocos empaques por tienda (típicamente 3-5), así que
    // filtrar el rango [cantidadMin, cantidadMax] en memoria (no expresable limpio como derived
    // query por el "cantidadMax IS NULL OR >=") es más claro que una query nativa para esto.
    List<TiendaEmpaque> findByActivoTrueOrderByCantidadMinAsc();
}
