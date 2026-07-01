package jaider.ecommerce.catalogo.subcategoria;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubcategoriaRepository extends JpaRepository<Subcategoria, Long> {
    List<Subcategoria> findAllByOrderByOrdenAscNombreAsc();
    List<Subcategoria> findByCatIdOrderByOrdenAscNombreAsc(Long catId);
}
