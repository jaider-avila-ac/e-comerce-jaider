package jaider.ecommerce.tienda;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Dominio público registrado para una tienda (PLAN_MEJORAS_API_ECOMMERCE_MULTITENANT.md §5).
 * Permite resolver el tenant de una solicitud pública a partir del Host real, en vez de confiar
 * únicamente en X-Tenant-Id (manipulable por el navegador).
 */
@Entity
@Table(name = "tienda_dominios")
@Getter @Setter
public class TiendaDominio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tdo_id")
    private Long id;

    @Column(name = "tdo_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "tdo_dominio", nullable = false, unique = true, length = 255)
    private String dominio;

    @Column(name = "tdo_principal", nullable = false)
    private boolean principal;

    @Column(name = "tdo_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "tdo_verificado_en")
    private OffsetDateTime verificadoEn;

    @Column(name = "tdo_creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
