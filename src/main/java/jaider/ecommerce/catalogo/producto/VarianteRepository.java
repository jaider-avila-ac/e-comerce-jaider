package jaider.ecommerce.catalogo.producto;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VarianteRepository extends JpaRepository<Variante, Long> {
    List<Variante> findByPrdIdOrderByIdAsc(Long prdId);

    void deleteByPrdId(Long prdId);
}
