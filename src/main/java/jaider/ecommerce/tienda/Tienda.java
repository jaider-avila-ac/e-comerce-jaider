package jaider.ecommerce.tienda;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tiendas")
@Getter @Setter
public class Tienda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tnd_id")
    private Long id;

    @Column(name = "tnd_slug", nullable = false, unique = true, length = 60)
    private String slug;

    @Column(name = "tnd_nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "tnd_logo_url", length = 512)
    private String logoUrl;

    @Column(name = "tnd_moneda", nullable = false, length = 3)
    private String moneda = "COP";

    @Column(name = "tnd_instagram", length = 255)
    private String instagram;

    @Column(name = "tnd_sitio_web", length = 255)
    private String sitioWeb;

    @Column(name = "tnd_tienda_url", length = 255)
    private String tiendaUrl;

    @Column(name = "tnd_whatsapp_principal", length = 20)
    private String whatsappPrincipal;

    @Column(name = "tnd_whatsapp_la_paz", length = 20)
    private String whatsappLaPaz;

    @Column(name = "tnd_envio_gratis_activo", nullable = false)
    private boolean envioGratisActivo = true;

    @Column(name = "tnd_envio_gratis_desde_centavos", nullable = false)
    private Long envioGratisDesdeCentavos = 20_000_000L;

    @Column(name = "tnd_envio_costo_centavos", nullable = false)
    private Long envioCostoCentavos = 1_200_000L;

    @Column(name = "tnd_dominio_staff", length = 120)
    private String dominioStaff;

    @Column(name = "tnd_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "tnd_creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
