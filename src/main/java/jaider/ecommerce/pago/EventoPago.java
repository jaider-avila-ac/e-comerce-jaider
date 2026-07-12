package jaider.ecommerce.pago;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "eventos_pago")
@Getter
@Setter
public class EventoPago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "evt_id")
    private Long id;

    @Column(name = "evt_pag_id")
    private Long pagId;

    @Column(name = "evt_ped_id")
    private Long pedId;

    @Column(name = "evt_tipo", nullable = false, length = 60)
    private String tipo;

    @Column(name = "evt_proveedor_id", length = 191)
    private String proveedorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evt_payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "evt_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
