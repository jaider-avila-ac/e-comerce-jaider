package jaider.ecommerce.catalogo.categoria;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "categorias")
@Getter
@Setter
public class Categoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cat_id")
    private Long id;

    @Column(name = "cat_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "cat_nombre", nullable = false, length = 100)
    private String nombre;

    @Column(name = "cat_slug", nullable = false, length = 100)
    private String slug;

    @Column(name = "cat_imagen_url", length = 512)
    private String imagenUrl;

    @Column(name = "cat_orden", nullable = false)
    private Short orden = 0;

    @Column(name = "cat_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "cat_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
