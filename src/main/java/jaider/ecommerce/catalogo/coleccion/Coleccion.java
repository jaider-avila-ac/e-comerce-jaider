package jaider.ecommerce.catalogo.coleccion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "colecciones")
@Getter
@Setter
public class Coleccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "col_id")
    private Long id;

    @Column(name = "col_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "col_nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "col_slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "col_descripcion", columnDefinition = "text")
    private String descripcion;

    @Column(name = "col_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "col_orden", nullable = false)
    private short orden = 0;

    @Column(name = "col_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "col_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
