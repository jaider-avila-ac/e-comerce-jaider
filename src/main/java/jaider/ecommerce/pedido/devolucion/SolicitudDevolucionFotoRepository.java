package jaider.ecommerce.pedido.devolucion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SolicitudDevolucionFotoRepository extends JpaRepository<SolicitudDevolucionFoto, Long> {
    List<SolicitudDevolucionFoto> findBySodIdOrderByOrdenAscIdAsc(Long sodId);
    void deleteBySodId(Long sodId);
}
