package jaider.ecommerce.auth.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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

    @Column(name = "emp_tnd_id")
    private Long tndId;

    @Column(name = "emp_nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "emp_apellido", length = 120)
    private String apellido;

    @Column(name = "emp_email", nullable = false, unique = true, length = 191)
    private String email;

    @Column(name = "emp_password_hash", nullable = false, length = 255)
    private String passwordHash;

    // Stored as String para compatibilidad con el tipo PostgreSQL rol_empleado
    @Column(name = "emp_rol", nullable = false, columnDefinition = "rol_empleado")
    private String rol;

    @Column(name = "emp_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "emp_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "emp_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
