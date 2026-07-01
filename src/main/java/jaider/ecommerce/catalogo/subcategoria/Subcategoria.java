package jaider.ecommerce.catalogo.subcategoria;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "subcategorias")
@Getter
@Setter
public class Subcategoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_id")
    private Long id;

    @Column(name = "sub_cat_id", nullable = false, updatable = false)
    private Long catId;

    @Column(name = "sub_nombre", nullable = false, length = 100)
    private String nombre;

    @Column(name = "sub_slug", nullable = false, length = 100)
    private String slug;

    @Column(name = "sub_orden", nullable = false)
    private Short orden = 0;

    @Column(name = "sub_activo", nullable = false)
    private boolean activo = true;
}
