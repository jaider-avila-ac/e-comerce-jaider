package jaider.ecommerce.catalogo.resena;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResenaRepository extends JpaRepository<Resena, Long> {

    boolean existsByPrdIdAndUsrId(Long prdId, Long usrId);

    List<Resena> findByPrdIdAndAprobadaTrueOrderByCreadoEnDesc(Long prdId);
}
