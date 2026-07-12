package jaider.ecommerce.auditoria;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {

    Page<Auditoria> findByTndIdOrderByCreadoEnDesc(Long tndId, Pageable pageable);

    Page<Auditoria> findByTndIdAndEntidadOrderByCreadoEnDesc(Long tndId, String entidad, Pageable pageable);
}
