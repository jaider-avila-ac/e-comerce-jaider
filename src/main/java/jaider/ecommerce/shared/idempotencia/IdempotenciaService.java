package jaider.ecommerce.shared.idempotencia;

import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.OffsetDateTime;

/**
 * Primitivas de BD para el patrón "adquirir clave de idempotencia" (ver REAUDITORIA_FUNCIONAL_
 * E_IDEMPOTENCIA.md, sección 5.1). El constraint único `uq_idm_operacion_clave` es la única
 * garantía real contra dos solicitudes concurrentes con la misma intención — todo lo demás
 * (estado visual, chequeo previo) puede perder la carrera.
 *
 * Métodos separados en vez de uno solo orquestador: intentarCrear() necesita su propia
 * transacción (REQUIRES_NEW) porque un INSERT que choca contra el constraint único dentro de una
 * transacción PostgreSQL la deja abortada — cualquier sentencia posterior en esa MISMA
 * transacción (incluido un SELECT) fallaría con "current transaction is aborted". Al aislarlo en
 * su propia transacción, un fallo ahí no contamina nada más. La orquestación completa vive en
 * IdempotenciaGuard (bean distinto, evita el problema de auto-invocación de @Transactional).
 */
@Service
@RequiredArgsConstructor
public class IdempotenciaService {

    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    public record Registro(Long idmId, String estado, String requestHash, String respuestaJson,
                            OffsetDateTime actualizadoEn) {
    }

    /** @throws ClaveDuplicadaException si ya existe una fila para esta clave (constraint único) —
     *  no es un error real, IdempotenciaGuard la atrapa y sigue con buscarPorClave(). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long intentarCrear(Long tndId, Long usrId, String operacion, String clave, String requestHash) {
        tenantSupport.applyTenant(em);
        try {
            Number idNum = (Number) em.createNativeQuery("""
                    INSERT INTO idempotencia_operaciones
                        (idm_tnd_id, idm_usr_id, idm_operacion, idm_clave, idm_request_hash)
                    VALUES (:tndId, :usrId, :operacion, :clave, :hash)
                    RETURNING idm_id
                    """)
                    .setParameter("tndId", tndId)
                    .setParameter("usrId", usrId)
                    .setParameter("operacion", operacion)
                    .setParameter("clave", clave)
                    .setParameter("hash", requestHash)
                    .getSingleResult();
            return idNum.longValue();
        } catch (PersistenceException e) {
            if (esViolacionDeUnicidad(e)) {
                throw new ClaveDuplicadaException();
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Registro buscarPorClave(Long tndId, Long usrId, String operacion, String clave) {
        tenantSupport.applyTenant(em);
        try {
            Object[] row = (Object[]) em.createNativeQuery("""
                    SELECT idm_id, idm_estado, idm_request_hash, idm_respuesta_json::text, idm_actualizado_en
                    FROM idempotencia_operaciones
                    WHERE idm_tnd_id = :tndId AND idm_usr_id = :usrId
                      AND idm_operacion = :operacion AND idm_clave = :clave
                    """)
                    .setParameter("tndId", tndId)
                    .setParameter("usrId", usrId)
                    .setParameter("operacion", operacion)
                    .setParameter("clave", clave)
                    .getSingleResult();
            return new Registro(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    row[4] instanceof OffsetDateTime odt ? odt : OffsetDateTime.parse(row[4].toString())
            );
        } catch (NoResultException e) {
            // Carrera extrema: intentarCrear() falló por duplicado pero para cuando llegamos
            // acá la fila ya no está (no debería pasar salvo borrado manual) — null fuerza a
            // IdempotenciaGuard a tratarlo como si pudiera reintentarse limpio.
            return null;
        }
    }

    /** Marca la fila como completada y guarda la respuesta para que cualquier reintento futuro
     *  con la misma clave la reciba tal cual, sin volver a ejecutar la operación real. */
    @Transactional
    public void completar(Long idmId, String respuestaJson, Long pedId) {
        tenantSupport.applyTenant(em);
        em.createNativeQuery("""
                UPDATE idempotencia_operaciones
                SET idm_estado = 'completado', idm_respuesta_json = CAST(:json AS jsonb), idm_ped_id = :pedId
                WHERE idm_id = :id
                """)
                .setParameter("json", respuestaJson)
                .setParameter("pedId", pedId)
                .setParameter("id", idmId)
                .executeUpdate();
    }

    /** "Reclama" una fila abandonada (quedó en 'procesando' más del timeout — el proceso que la
     *  creó murió antes de completar) para que un reintento la use como si fuera nueva. */
    @Transactional
    public void reclamar(Long idmId) {
        tenantSupport.applyTenant(em);
        em.createNativeQuery("UPDATE idempotencia_operaciones SET idm_actualizado_en = now() WHERE idm_id = :id")
                .setParameter("id", idmId)
                .executeUpdate();
    }

    /** Libera la clave (borra la fila) cuando la operación real lanzó una excepción ANTES de
     *  producir ningún efecto de negocio persistente — así el cliente puede corregir el problema
     *  y reintentar con la MISMA clave sin esperar el timeout completo. Solo debe llamarse desde
     *  puntos donde se sabe con certeza que no quedó nada a medias (ver comentarios en cada
     *  llamador — ej. PedidoCheckoutService, antes de cualquier cobro real a la pasarela). */
    @Transactional
    public void liberar(Long idmId) {
        tenantSupport.applyTenant(em);
        em.createNativeQuery("DELETE FROM idempotencia_operaciones WHERE idm_id = :id AND idm_estado = 'procesando'")
                .setParameter("id", idmId)
                .executeUpdate();
    }

    // "23505" = unique_violation en el estándar SQLSTATE de Postgres — se comprueba vía
    // java.sql.SQLException (JDK estándar) en vez de org.postgresql.util.PSQLException porque el
    // driver de Postgres es una dependencia solo de runtime en este proyecto (no compila contra
    // ella), y SQLException.getSQLState() ya expone el mismo código sin esa dependencia.
    private boolean esViolacionDeUnicidad(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}