package jaider.ecommerce.catalogo.pregunta;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Snapshot del texto ANTERIOR cada vez que se edita una pregunta o su respuesta — para que el
 *  admin pueda ver "qué decía antes" sin perder la versión vieja al guardar la nueva. */
@Entity
@Table(name = "producto_pregunta_ediciones")
@Getter
@Setter
public class PreguntaEdicion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pge_id")
    private Long id;

    @Column(name = "pge_preg_id", nullable = false, updatable = false)
    private Long pregId;

    // 'pregunta' | 'respuesta'
    @Column(name = "pge_campo", nullable = false, updatable = false, length = 20)
    private String campo;

    @Column(name = "pge_texto_anterior", nullable = false, updatable = false)
    private String textoAnterior;

    @Column(name = "pge_editado_en", insertable = false, updatable = false)
    private OffsetDateTime editadoEn;
}
