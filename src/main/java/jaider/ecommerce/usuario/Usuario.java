package jaider.ecommerce.usuario;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "usuarios")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usr_id")
    private Long id;

    @Column(name = "usr_email", length = 191, nullable = false)
    private String email;

    // auth_provider es un enum personalizado de PostgreSQL → insertable/updatable=false
    // Todos los INSERTs usan native query con CAST(:val AS auth_provider)
    @Column(name = "usr_provider", insertable = false, updatable = false)
    private String provider;

    @Column(name = "usr_google_id", length = 128)
    private String googleId;

    @Column(name = "usr_password_hash")
    private String passwordHash;

    @Column(name = "usr_reset_token", length = 100)
    private String resetToken;

    @Column(name = "usr_reset_expiry")
    private OffsetDateTime resetExpiry;

    @Column(name = "usr_acepto_terminos", nullable = false)
    private boolean aceptoTerminos;

    @Builder.Default
    @Column(name = "usr_activo", nullable = false)
    private boolean activo = true;

    // usr_tnd_id es NOT NULL sin default → siempre via native query
    @Column(name = "usr_tnd_id", nullable = false, insertable = false, updatable = false)
    private Long tndId;

    @Column(name = "usr_creado_en", insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "usr_actualizado_en", insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
