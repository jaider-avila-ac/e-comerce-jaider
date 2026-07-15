package jaider.ecommerce.pedido.devolucion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DireccionDevolucionRepository extends JpaRepository<DireccionDevolucion, Long> {
    List<DireccionDevolucion> findAllByOrderByNombreAsc();
    List<DireccionDevolucion> findByActivoTrueOrderByNombreAsc();
}
