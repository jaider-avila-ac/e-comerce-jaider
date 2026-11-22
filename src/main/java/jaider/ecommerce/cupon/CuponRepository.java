package jaider.ecommerce.cupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CuponRepository extends JpaRepository<Cupon, Long> {

    Optional<Cupon> findByTndIdAndCodigo(Long tndId, String codigo);

    List<Cupon> findByTndIdOrderByCreadoEnDesc(Long tndId);

    /** Canje atómico: una sola sentencia UPDATE condicionada, nunca lee-y-luego-escribe — así dos
     *  canjes simultáneos justo en el último uso disponible no dejan pasar a los dos (mismo
     *  criterio que el resto del proyecto para evitar condiciones de carrera, ver
     *  REAUDITORIA_FUNCIONAL_E_IDEMPOTENCIA.md). Devuelve cuántas filas afectó: 1 si el canje
     *  contó, 0 si para ese momento ya no quedaban usos (o ya no está activo). */
    @Modifying
    @Query(value = """
            UPDATE cupones SET cup_usos_actuales = cup_usos_actuales + 1
            WHERE cup_id = :id AND cup_activo = true AND cup_usos_actuales < cup_max_usos
            """, nativeQuery = true)
    int canjear(@Param("id") Long id);
}
