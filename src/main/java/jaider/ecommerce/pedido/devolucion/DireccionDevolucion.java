package jaider.ecommerce.pedido.devolucion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "direcciones_devolucion")
@Getter
@Setter
public class DireccionDevolucion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dvd_id")
    private Long id;

    @Column(name = "dvd_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "dvd_nombre", nullable = false, length = 100)
    private String nombre;

    @Column(name = "dvd_direccion", nullable = false, length = 255)
    private String direccion;

    @Column(name = "dvd_complemento", length = 255)
    private String complemento;

    @Column(name = "dvd_departamento", nullable = false, length = 100)
    private String departamento;

    @Column(name = "dvd_municipio", nullable = false, length = 100)
    private String municipio;

    @Column(name = "dvd_barrio", length = 100)
    private String barrio;

    @Column(name = "dvd_contacto_nombre", length = 150)
    private String contactoNombre;

    @Column(name = "dvd_contacto_telefono", length = 40)
    private String contactoTelefono;

    @Column(name = "dvd_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "dvd_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
