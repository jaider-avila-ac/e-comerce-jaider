package jaider.ecommerce.auth.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmpleadoRepository extends JpaRepository<Empleado, Long> {
    Optional<Empleado> findByAdminUserId(Long adminUserId);
}
