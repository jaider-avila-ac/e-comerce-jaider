package jaider.ecommerce.tienda.banner;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "banners")
@Getter
@Setter
public class Banner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ban_id")
    private Long id;

    @Column(name = "ban_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    // ban_posicion: enum posicion_banner ('hero' | 'promo') → se escribe vía native query con CAST
    @Column(name = "ban_posicion", columnDefinition = "posicion_banner", insertable = false, updatable = false)
    private String posicion;

    // ban_tipo: enum tipo_media ('imagen' | 'video') → se escribe vía native query con CAST
    @Column(name = "ban_tipo", columnDefinition = "tipo_media", insertable = false, updatable = false)
    private String tipo;

    @Column(name = "ban_url", nullable = false, length = 512)
    private String url;

    @Column(name = "ban_titulo", length = 150)
    private String titulo;

    @Column(name = "ban_cta_link", length = 255)
    private String ctaLink;

    @Column(name = "ban_orden", nullable = false)
    private Short orden = 0;

    @Column(name = "ban_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "ban_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
