package jaider.ecommerce.auth.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "empleados")
@Getter
@Setter
public class Empleado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "emp_id")
    private Long id;

    @Column(name = "emp_adm_id", nullable = false, unique = true)
    private Long adminUserId;

    @Column(name = "emp_apellido", length = 120)
    private String apellido;

    @Column(name = "emp_telefono", length = 20)
    private String telefono;

    @Column(name = "emp_avatar", length = 512)
    private String avatar;

    @Column(name = "emp_cargo", length = 100)
    private String cargo;

    // Stored as String para compatibilidad con el tipo PostgreSQL tipo_documento
    @Column(name = "emp_tipo_documento", columnDefinition = "tipo_documento")
    private String tipoDocumento;

    @Column(name = "emp_numero_documento", length = 30)
    private String numeroDocumento;

    @Column(name = "emp_fecha_nacimiento")
    private LocalDate fechaNacimiento;

    @Column(name = "emp_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "emp_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
