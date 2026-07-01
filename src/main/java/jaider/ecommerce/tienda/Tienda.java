package jaider.ecommerce.tienda;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tnd_config", columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "tnd_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "tnd_creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
