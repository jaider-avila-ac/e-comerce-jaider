package jaider.ecommerce.catalogo.producto;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "variantes")
@Getter
@Setter
public class Variante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "var_id")
    private Long id;

    @Column(name = "var_prd_id", nullable = false, updatable = false)
    private Long prdId;

    @Column(name = "var_talla", length = 20)
    private String talla;

    @Column(name = "var_color", length = 60)
    private String color;

    @Column(name = "var_color_hex", length = 7)
    private String colorHex;

    @Column(name = "var_stock", nullable = false)
    private Integer stock = 0;

    @Column(name = "var_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "var_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "var_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
