package jaider.ecommerce.pago;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "pagos")
@Getter
@Setter
public class Pago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pag_id")
    private Long id;

    @Column(name = "pag_ped_id", nullable = false, updatable = false)
    private Long pedId;

    @Column(name = "pag_usr_id", nullable = false, updatable = false)
    private Long usrId;

    @Column(name = "pag_referencia", nullable = false, updatable = false, length = 100)
    private String referencia;

    @Column(name = "pag_gateway_tx_id", length = 150)
    private String gatewayTxId;

    // enums Postgres personalizados — se leen como String; las escrituras van por CAST en query nativa
    @Column(name = "pag_proveedor", nullable = false, columnDefinition = "proveedor_pago")
    private String proveedor;

    @Column(name = "pag_metodo", columnDefinition = "metodo_pago")
    private String metodo;

    @Column(name = "pag_estado", nullable = false, columnDefinition = "estado_pago")
    private String estado;

    @Column(name = "pag_monto_centavos", nullable = false)
    private Long montoCentavos;

    @Column(name = "pag_moneda", nullable = false, length = 3)
    private String moneda;

    @Column(name = "pag_ip_origen")
    private String ipOrigen;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pag_respuesta_json", columnDefinition = "jsonb")
    private Map<String, Object> respuestaJson;

    @Column(name = "pag_motivo_rechazo", columnDefinition = "text")
    private String motivoRechazo;

    @Column(name = "pag_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "pag_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
