package jaider.ecommerce.usuario;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query(value = "SELECT * FROM usuarios WHERE usr_reset_token = :token LIMIT 1", nativeQuery = true)
    Optional<Usuario> findByResetToken(@Param("token") String token);
}
