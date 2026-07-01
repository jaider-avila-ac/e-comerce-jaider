package jaider.ecommerce.auth.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminDataInitializer implements ApplicationRunner {

    private final EmpleadoRepository repo;
    private final PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repo.findByEmail("admin@calzacaribe.com").isPresent()) return;

        // Usamos native query con CAST explícito porque rol_empleado es un enum PostgreSQL
        em.createNativeQuery("""
                INSERT INTO empleados (emp_nombre, emp_apellido, emp_email, emp_password_hash, emp_rol, emp_activo)
                VALUES (:nombre, :apellido, :email, :hash, CAST(:rol AS rol_empleado), true)
                """)
          .setParameter("nombre",   "Admin")
          .setParameter("apellido", "Calzacaribe")
          .setParameter("email",    "admin@calzacaribe.com")
          .setParameter("hash",     passwordEncoder.encode("admin123"))
          .setParameter("rol",      "superadmin")
          .executeUpdate();

        log.warn("=== Admin creado: admin@calzacaribe.com / admin123 — cambia la contraseña en producción ===");
    }
}
