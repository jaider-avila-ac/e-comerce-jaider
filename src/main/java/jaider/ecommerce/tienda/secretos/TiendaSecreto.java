package jaider.ecommerce.tienda.secretos;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Una credencial cifrada de UNA tienda para UN proveedor y UN campo (ej. proveedor="WOMPI",
 * campo="PRIVATE_KEY"). El valor NUNCA se guarda ni se expone en texto plano — ver
 * {@link SecretEncryptionService}.
 */
@Entity
@Table(name = "tienda_secretos")
@Getter
@Setter
public class TiendaSecreto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tse_id")
    private Long id;

    @Column(name = "tse_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "tse_proveedor", nullable = false, updatable = false, length = 20)
    private String proveedor;

    @Column(name = "tse_campo", nullable = false, updatable = false, length = 30)
    private String campo;

    @Column(name = "tse_valor_cifrado", nullable = false)
    private String valorCifrado;

    @Column(name = "tse_actualizado_en", insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;

    @Column(name = "tse_actualizado_por")
    private Long actualizadoPor;
}
