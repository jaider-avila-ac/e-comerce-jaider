package jaider.ecommerce.pedido;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "pedidos")
@Getter
@Setter
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ped_id")
    private Long id;

    @Column(name = "ped_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "ped_usr_id", nullable = false, updatable = false)
    private Long usrId;

    @Column(name = "ped_numero", nullable = false, updatable = false, length = 25)
    private String numero;

    // estado_pedido es un tipo enum personalizado de PostgreSQL — se mapea como String
    @Column(name = "ped_estado", nullable = false, columnDefinition = "estado_pedido")
    private String estado;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ped_dir_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> dirSnapshot;

    @Column(name = "ped_subtotal_centavos", nullable = false)
    private Long subtotalCentavos;

    @Column(name = "ped_descuento_centavos", nullable = false)
    private Long descuentoCentavos = 0L;

    @Column(name = "ped_envio_centavos", nullable = false)
    private Long envioCentavos = 0L;

    @Column(name = "ped_total_centavos", nullable = false)
    private Long totalCentavos;

    @Column(name = "ped_notas", columnDefinition = "text")
    private String notas;

    @Column(name = "ped_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "ped_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
