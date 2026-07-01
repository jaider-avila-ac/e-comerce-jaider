package jaider.ecommerce.pedido;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "pedido_items")
@Getter
@Setter
public class PedidoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pi_id")
    private Long id;

    @Column(name = "pi_ped_id", nullable = false, updatable = false)
    private Long pedId;

    @Column(name = "pi_prd_id")
    private Long prdId;

    @Column(name = "pi_var_id")
    private Long varId;

    @Column(name = "pi_nombre_snap", nullable = false, length = 191)
    private String nombreSnap;

    @Column(name = "pi_imagen_snap", length = 512)
    private String imagenSnap;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pi_variantes_snap", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> variantesSnap;

    @Column(name = "pi_precio_unitario_centavos", nullable = false)
    private Long precioUnitarioCentavos;

    @Column(name = "pi_descuento_unitario_centavos", nullable = false)
    private Long descuentoUnitarioCentavos = 0L;

    @Column(name = "pi_cantidad", nullable = false)
    private Integer cantidad;

    @Column(name = "pi_subtotal_centavos", nullable = false)
    private Long subtotalCentavos;
}
