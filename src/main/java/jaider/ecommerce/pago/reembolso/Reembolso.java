package jaider.ecommerce.pago.reembolso;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "reembolsos")
@Getter
@Setter
public class Reembolso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ref_id")
    private Long id;

    @Column(name = "ref_pag_id", nullable = false, updatable = false)
    private Long pagId;

    @Column(name = "ref_ped_id", nullable = false, updatable = false)
    private Long pedId;

    @Column(name = "ref_usr_id", nullable = false, updatable = false)
    private Long usrId;

    @Column(name = "ref_monto_centavos", nullable = false, updatable = false)
    private Long montoCentavos;

    @Column(name = "ref_motivo", columnDefinition = "text", updatable = false)
    private String motivo;

    // estado_reembolso es un enum personalizado de Postgres — mismo patrón que SolicitudDevolucion:
    // insertable = false para que el INSERT tome el DEFAULT ('pendiente'); cualquier transición
    // pasa por UPDATE nativo con CAST (ver ReembolsoRepository), nunca por repo.save().
    @Column(name = "ref_estado", nullable = false, columnDefinition = "estado_reembolso", insertable = false)
    private String estado;

    @Column(name = "ref_gateway_ref", length = 100)
    private String gatewayRef;

    @Column(name = "ref_origen", nullable = false, updatable = false, length = 30)
    private String origen;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ref_gateway_respuesta", columnDefinition = "jsonb")
    private Map<String, Object> gatewayRespuesta;

    @Column(name = "ref_error_mensaje", columnDefinition = "text")
    private String errorMensaje;

    @Column(name = "ref_confirmado_en")
    private OffsetDateTime confirmadoEn;

    @Column(name = "ref_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "ref_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
