package jaider.ecommerce.catalogo.pregunta;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreguntaEdicionRepository extends JpaRepository<PreguntaEdicion, Long> {
    List<PreguntaEdicion> findByPregIdOrderByEditadoEnDesc(Long pregId);
}
