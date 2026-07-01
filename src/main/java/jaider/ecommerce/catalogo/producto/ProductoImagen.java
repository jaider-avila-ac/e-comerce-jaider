package jaider.ecommerce.catalogo.producto;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "producto_imagenes")
@Getter
@Setter
public class ProductoImagen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pi_id")
    private Long id;

    @Column(name = "pi_prd_id", nullable = false, updatable = false)
    private Long prdId;

    @Column(name = "pi_var_id")
    private Long varId;

    @Column(name = "pi_url", nullable = false, length = 512)
    private String url;

    // pi_tipo: tipo_media enum → insertable=false para usar el DEFAULT 'imagen' del DB
    @Column(name = "pi_tipo", columnDefinition = "tipo_media", insertable = false, updatable = false)
    private String tipo;

    @Column(name = "pi_orden", nullable = false)
    private Short orden = 0;

    @Column(name = "pi_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
