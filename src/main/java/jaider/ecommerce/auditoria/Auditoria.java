package jaider.ecommerce.auditoria;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "auditoria_acciones")
@Getter
@Setter
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "aud_id")
    private Long id;

    @Column(name = "aud_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "aud_admin_id", nullable = false, updatable = false)
    private Long adminId;

    @Column(name = "aud_accion", nullable = false, length = 60, updatable = false)
    private String accion;

    @Column(name = "aud_entidad", length = 60, updatable = false)
    private String entidad;

    @Column(name = "aud_entidad_id", updatable = false)
    private Long entidadId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aud_detalle", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> detalle = Map.of();

    @Column(name = "aud_creado_en", insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
