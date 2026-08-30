package jaider.ecommerce.catalogo.pregunta;

import jaider.ecommerce.notificacion.event.PreguntaCreadaEvent;
import jaider.ecommerce.notificacion.event.PreguntaRespondidaEvent;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PreguntaService {

    private final PreguntaRepository preguntaRepo;
    private final PreguntaEdicionRepository edicionRepo;
    private final TenantSupport tenantSupport;
    private final ApplicationEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager em;

    // ─── Público (tienda) ───────────────────────────────────────────────────

    /** usrId puede ser null (visitante sin sesión, o endpoint público sin auth) — en ese caso
     *  "esMia" siempre da false, nunca se rompe por falta de token. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<PreguntaResponse> listarPublicas(Long prdId, Long usrId) {
        tenantSupport.applyTenant(em);

        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.preg_id, p.preg_texto, p.preg_editada, p.preg_respuesta_texto,
                       p.preg_respondida_en, p.preg_creado_en, p.preg_usr_id,
                       cp.cp_nombre, cp.cp_apellido
                FROM producto_preguntas p
                LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = p.preg_usr_id
                WHERE p.preg_prd_id = :prdId AND p.preg_eliminada = false
                ORDER BY p.preg_creado_en DESC
                """)
                .setParameter("prdId", prdId)
                .getResultList();

        List<PreguntaResponse> result = new ArrayList<>();
        for (Object[] row : rows) {
            Long autorId = ((Number) row[6]).longValue();
            result.add(new PreguntaResponse(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    (Boolean) row[2],
                    autorNombre((String) row[7], (String) row[8]),
                    usrId != null && usrId.equals(autorId),
                    (String) row[3],
                    toOffsetDateTime(row[4]),
                    toOffsetDateTime(row[5])
            ));
        }
        return result;
    }

    @Transactional
    public PreguntaResponse crear(Long prdId, Long usrId, Long tndId, PreguntaRequest req) {
        tenantSupport.applyTenant(em);

        String texto = blankToNull(req.texto());
        if (texto == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe tu pregunta");
        }
        if (texto.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La pregunta es demasiado larga");
        }

        String prdNombre;
        try {
            prdNombre = (String) em.createNativeQuery(
                    "SELECT prd_nombre FROM productos WHERE prd_id = :id AND prd_activo = true")
                    .setParameter("id", prdId)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado");
        }

        Pregunta pregunta = new Pregunta();
        pregunta.setTndId(tndId);
        pregunta.setPrdId(prdId);
        pregunta.setUsrId(usrId);
        pregunta.setTexto(texto);
        pregunta = preguntaRepo.save(pregunta);

        String clienteNombre = nombreCliente(usrId);
        eventPublisher.publishEvent(new PreguntaCreadaEvent(tndId, pregunta.getId(), prdId, prdNombre, clienteNombre));

        return new PreguntaResponse(
                pregunta.getId(), texto, false, "Tú", true, null, null, OffsetDateTime.now());
    }

    /** Solo la propia, y solo mientras no la hayan borrado (ni ella misma ni el admin) — editar
     *  algo ya moderado/borrado no tiene sentido. Guarda el texto viejo en el historial antes de
     *  pisarlo, para que el admin pueda ver "qué decía antes". */
    @Transactional
    public void editar(Long pregId, Long usrId, Long tndId, PreguntaRequest req) {
        tenantSupport.applyTenant(em);
        Pregunta pregunta = preguntaRepo.findById(pregId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pregunta no encontrada"));

        if (!usrId.equals(pregunta.getUsrId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes editar la pregunta de otra persona");
        }
        if (pregunta.isEliminada()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta pregunta ya fue eliminada");
        }
        if (pregunta.getRespuestaTexto() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta pregunta ya fue respondida, no se puede editar");
        }
        String texto = blankToNull(req.texto());
        if (texto == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe tu pregunta");
        }

        guardarHistorial(pregId, "pregunta", pregunta.getTexto());
        pregunta.setTexto(texto);
        pregunta.setEditada(true);
    }

    /** El propio cliente retira su pregunta — queda "eliminada" (visible para el admin, no para
     *  la tienda), igual que cuando la borra el admin; solo cambia quién quedó registrado. */
    @Transactional
    public void eliminarPropia(Long pregId, Long usrId, Long tndId) {
        tenantSupport.applyTenant(em);
        Pregunta pregunta = preguntaRepo.findById(pregId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pregunta no encontrada"));

        if (!usrId.equals(pregunta.getUsrId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes eliminar la pregunta de otra persona");
        }
        marcarEliminada(pregunta, "cliente");
    }

    // ─── Admin ──────────────────────────────────────────────────────────────

    /** estado: null/"" = todas, "pendiente" = sin responder y no eliminadas, "respondida" =
     *  con respuesta y no eliminadas, "eliminada" = solo las borradas (por quien sea). */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<PreguntaAdminResponse> listarAdmin(String estado, Long prdId) {
        tenantSupport.applyTenant(em);

        StringBuilder where = new StringBuilder(" WHERE (CAST(:prdId AS BIGINT) IS NULL OR p.preg_prd_id = CAST(:prdId AS BIGINT)) ");
        if ("pendiente".equals(estado)) {
            where.append(" AND p.preg_eliminada = false AND p.preg_respuesta_texto IS NULL ");
        } else if ("respondida".equals(estado)) {
            where.append(" AND p.preg_eliminada = false AND p.preg_respuesta_texto IS NOT NULL ");
        } else if ("eliminada".equals(estado)) {
            where.append(" AND p.preg_eliminada = true ");
        }

        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.preg_id, p.preg_prd_id, prd.prd_nombre,
                       u.usr_email, cp.cp_nombre, cp.cp_apellido,
                       p.preg_texto, p.preg_editada, p.preg_eliminada, p.preg_eliminada_por, p.preg_eliminada_en,
                       p.preg_respuesta_texto, p.preg_respuesta_admin_id, au.nombre,
                       p.preg_respuesta_editada, p.preg_respondida_en, p.preg_creado_en
                FROM producto_preguntas p
                LEFT JOIN productos prd ON prd.prd_id = p.preg_prd_id
                LEFT JOIN usuarios u ON u.usr_id = p.preg_usr_id
                LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = p.preg_usr_id
                LEFT JOIN admin_users au ON au.id = p.preg_respuesta_admin_id
                """ + where + """
                ORDER BY p.preg_creado_en DESC
                """)
                .setParameter("prdId", prdId)
                .getResultList();

        List<PreguntaAdminResponse> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(new PreguntaAdminResponse(
                    ((Number) row[0]).longValue(),
                    row[1] != null ? ((Number) row[1]).longValue() : null,
                    (String) row[2],
                    autorNombre((String) row[4], (String) row[5]),
                    (String) row[3],
                    (String) row[6],
                    (Boolean) row[7],
                    (Boolean) row[8],
                    (String) row[9],
                    toOffsetDateTime(row[10]),
                    (String) row[11],
                    row[12] != null ? ((Number) row[12]).longValue() : null,
                    (String) row[13],
                    (Boolean) row[14],
                    toOffsetDateTime(row[15]),
                    toOffsetDateTime(row[16])
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<PreguntaEdicionResponse> historial(Long pregId) {
        tenantSupport.applyTenant(em);
        return edicionRepo.findByPregIdOrderByEditadoEnDesc(pregId).stream()
                .map(e -> new PreguntaEdicionResponse(e.getCampo(), e.getTextoAnterior(), e.getEditadoEn()))
                .toList();
    }

    /** Responder por primera vez, o corregir una respuesta ya dada (guarda la anterior en el
     *  historial). Solo avisa al cliente la primera vez — corregir una respuesta ya notificada
     *  no dispara un segundo aviso, para no spamear por cada ajuste de redacción. */
    @Transactional
    public void responder(Long pregId, Long adminId, ResponderPreguntaRequest req) {
        tenantSupport.applyTenant(em);
        Pregunta pregunta = preguntaRepo.findById(pregId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pregunta no encontrada"));
        if (pregunta.isEliminada()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta pregunta fue eliminada, no se puede responder");
        }
        String texto = blankToNull(req.texto());
        if (texto == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe una respuesta");
        }

        boolean esPrimeraRespuesta = pregunta.getRespuestaTexto() == null;
        if (!esPrimeraRespuesta) {
            guardarHistorial(pregId, "respuesta", pregunta.getRespuestaTexto());
            pregunta.setRespuestaEditada(true);
        } else {
            pregunta.setRespondidaEn(OffsetDateTime.now());
        }
        pregunta.setRespuestaTexto(texto);
        pregunta.setRespuestaAdminId(adminId);

        if (esPrimeraRespuesta && pregunta.getPrdId() != null) {
            String prdNombre = (String) em.createNativeQuery("SELECT prd_nombre FROM productos WHERE prd_id = :id")
                    .setParameter("id", pregunta.getPrdId())
                    .getSingleResult();
            eventPublisher.publishEvent(new PreguntaRespondidaEvent(
                    pregunta.getTndId(), pregunta.getUsrId(), pregId, pregunta.getPrdId(), prdNombre));
        }
    }

    @Transactional
    public void eliminarAdmin(Long pregId) {
        tenantSupport.applyTenant(em);
        Pregunta pregunta = preguntaRepo.findById(pregId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pregunta no encontrada"));
        marcarEliminada(pregunta, "admin");
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private void marcarEliminada(Pregunta pregunta, String por) {
        pregunta.setEliminada(true);
        pregunta.setEliminadaPor(por);
        pregunta.setEliminadaEn(OffsetDateTime.now());
    }

    private void guardarHistorial(Long pregId, String campo, String textoAnterior) {
        PreguntaEdicion edicion = new PreguntaEdicion();
        edicion.setPregId(pregId);
        edicion.setCampo(campo);
        edicion.setTextoAnterior(textoAnterior);
        edicionRepo.save(edicion);
    }

    private String nombreCliente(Long usrId) {
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                    "SELECT cp_nombre, cp_apellido FROM clientes_perfil WHERE cp_usr_id = :id")
                    .setParameter("id", usrId)
                    .getSingleResult();
            return autorNombre((String) row[0], (String) row[1]);
        } catch (NoResultException e) {
            return "Un cliente";
        }
    }

    private static String autorNombre(String nombre, String apellido) {
        if (nombre == null || nombre.isBlank()) return "Cliente";
        String inicialApellido = (apellido != null && !apellido.isBlank())
                ? " " + Character.toUpperCase(apellido.charAt(0)) + "."
                : "";
        return nombre + inicialApellido;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        return switch (value) {
            case java.time.Instant instant -> instant.atOffset(java.time.ZoneOffset.UTC);
            case java.sql.Timestamp ts -> ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
            case OffsetDateTime odt -> odt;
            case null, default -> null;
        };
    }
}
