package jaider.ecommerce.auth.admin;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final Set<String> ROLES_ASIGNABLES = Set.of("colaborador", "bodega");

    private final AdminUserRepository adminUserRepository;
    private final TiendaRepository tiendaRepository;
    private final AuditoriaService auditoriaService;
    private final PasswordEncoder passwordEncoder;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listar() {
        tenantSupport.applyTenant(em);
        Long tiendaId = currentTiendaId();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.id, a.email, a.nombre, a.rol::text, a.activo,
                       e.emp_apellido, e.emp_telefono, e.emp_cargo,
                       e.emp_tipo_documento::text, e.emp_numero_documento, e.emp_fecha_nacimiento
                FROM admin_users a
                LEFT JOIN empleados e ON e.emp_adm_id = a.id
                WHERE a.tienda_id = :tiendaId AND a.rol <> CAST('superadmin' AS rol_empleado)
                ORDER BY a.nombre
                """)
                .setParameter("tiendaId", tiendaId)
                .getResultList();

        List<AdminUserResponse> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(new AdminUserResponse(
                    ((Number) row[0]).longValue(), (String) row[1], (String) row[2], (String) row[3],
                    Boolean.TRUE.equals(row[4]), (String) row[5], (String) row[6], (String) row[7],
                    (String) row[8], (String) row[9], row[10] != null ? row[10].toString() : null
            ));
        }
        return result;
    }

    @Transactional
    public AdminUserResponse crear(AdminUser actor, AdminUserRequest req) {
        tenantSupport.applyTenant(em);

        if (req.rol() == null || !ROLES_ASIGNABLES.contains(req.rol().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo puedes crear colaboradores o personal de bodega");
        }
        if (req.usuario() == null || req.usuario().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El usuario es obligatorio");
        }
        if (req.password() == null || req.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe tener al menos 8 caracteres");
        }
        if (req.nombre() == null || req.nombre().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }

        Long tiendaId = currentTiendaId();
        Tienda tienda = tiendaRepository.findById(tiendaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
        if (tienda.getDominioStaff() == null || tienda.getDominioStaff().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Configura el dominio de tu tienda en Configuración antes de crear colaboradores");
        }

        String usuarioNormalizado = req.usuario().trim().toLowerCase().replaceAll("\\s+", ".");
        String email = usuarioNormalizado + "@" + tienda.getDominioStaff();

        if (adminUserRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario con ese nombre en esta tienda");
        }

        String rol = req.rol().toLowerCase();
        String hash = passwordEncoder.encode(req.password());
        String nombre = req.nombre().trim();

        String numeroDocumento = blankToNull(req.numeroDocumento());
        String tipoDocumento = numeroDocumento == null ? null
                : (blankToNull(req.tipoDocumento()) == null ? "CC" : req.tipoDocumento().trim().toUpperCase());
        String apellido = blankToNull(req.apellido());
        String telefono = blankToNull(req.telefono());
        String cargo = blankToNull(req.cargo());
        String fechaNacimiento = blankToNull(req.fechaNacimiento());

        if (numeroDocumento != null && existeDocumentoEnEmpleados(tiendaId, tipoDocumento, numeroDocumento, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ese número de documento ya está registrado por otro colaborador en esta tienda");
        }

        // rol_empleado es un enum nativo de Postgres — el bind directo de un String vía JPA
        // no castea automáticamente, por eso el INSERT se hace con CAST explícito (mismo
        // patrón que usa el resto del proyecto para columnas de enums de Postgres).
        Number nuevoId = (Number) em.createNativeQuery("""
                INSERT INTO admin_users (email, password, nombre, rol, tienda_id, activo)
                VALUES (:email, :password, :nombre, CAST(:rol AS rol_empleado), :tiendaId, true)
                RETURNING id
                """)
                .setParameter("email", email)
                .setParameter("password", hash)
                .setParameter("nombre", nombre)
                .setParameter("rol", rol)
                .setParameter("tiendaId", tiendaId)
                .getSingleResult();
        Long adminId = nuevoId.longValue();

        // Perfil 1-a-1 del colaborador — se crea siempre junto con el admin_user, aunque
        // los campos de detalle vengan vacíos, para que después se puedan editar sin upsert.
        em.createNativeQuery("""
                INSERT INTO empleados (emp_adm_id, emp_tnd_id, emp_apellido, emp_telefono, emp_cargo,
                                        emp_tipo_documento, emp_numero_documento, emp_fecha_nacimiento)
                VALUES (:adminId, :tiendaId, :apellido, :telefono, :cargo,
                        CAST(:tipoDocumento AS tipo_documento), :numeroDocumento, CAST(:fechaNacimiento AS date))
                """)
                .setParameter("adminId", adminId)
                .setParameter("tiendaId", tiendaId)
                .setParameter("apellido", apellido)
                .setParameter("telefono", telefono)
                .setParameter("cargo", cargo)
                .setParameter("tipoDocumento", tipoDocumento)
                .setParameter("numeroDocumento", numeroDocumento)
                .setParameter("fechaNacimiento", fechaNacimiento)
                .executeUpdate();

        auditoriaService.registrar(tiendaId, actor.getId(), "colaborador.creado", "admin_user", adminId,
                Map.of("rol", rol, "email", email));

        return new AdminUserResponse(adminId, email, nombre, rol, true,
                apellido, telefono, cargo, tipoDocumento, numeroDocumento, fechaNacimiento);
    }

    @Transactional
    public AdminUserResponse actualizar(AdminUser actor, Long id, AdminUserUpdateRequest req) {
        tenantSupport.applyTenant(em);
        Long tiendaId = currentTiendaId();

        AdminUser objetivo = adminUserRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        if ("superadmin".equals(objetivo.getRol())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes modificar a un superadmin");
        }
        if (req.nombre() == null || req.nombre().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }

        String nombre = req.nombre().trim();
        em.createNativeQuery("UPDATE admin_users SET nombre = :nombre WHERE id = :id")
                .setParameter("nombre", nombre)
                .setParameter("id", id)
                .executeUpdate();

        String numeroDocumento = blankToNull(req.numeroDocumento());
        String tipoDocumento = numeroDocumento == null ? null
                : (blankToNull(req.tipoDocumento()) == null ? "CC" : req.tipoDocumento().trim().toUpperCase());
        String apellido = blankToNull(req.apellido());
        String telefono = blankToNull(req.telefono());
        String cargo = blankToNull(req.cargo());
        String fechaNacimiento = blankToNull(req.fechaNacimiento());

        if (numeroDocumento != null && existeDocumentoEnEmpleados(tiendaId, tipoDocumento, numeroDocumento, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ese número de documento ya está registrado por otro colaborador en esta tienda");
        }

        em.createNativeQuery("""
                UPDATE empleados SET emp_apellido = :apellido, emp_telefono = :telefono, emp_cargo = :cargo,
                       emp_tipo_documento = CAST(:tipoDocumento AS tipo_documento),
                       emp_numero_documento = :numeroDocumento,
                       emp_fecha_nacimiento = CAST(:fechaNacimiento AS date)
                WHERE emp_adm_id = :id
                """)
                .setParameter("apellido", apellido)
                .setParameter("telefono", telefono)
                .setParameter("cargo", cargo)
                .setParameter("tipoDocumento", tipoDocumento)
                .setParameter("numeroDocumento", numeroDocumento)
                .setParameter("fechaNacimiento", fechaNacimiento)
                .setParameter("id", id)
                .executeUpdate();

        auditoriaService.registrar(tiendaId, actor.getId(), "colaborador.editado", "admin_user", id, Map.of());

        return new AdminUserResponse(id, objetivo.getEmail(), nombre, objetivo.getRol(), objetivo.isActivo(),
                apellido, telefono, cargo, tipoDocumento, numeroDocumento, fechaNacimiento);
    }

    @Transactional
    public AdminUserResponse cambiarActivo(AdminUser actor, Long id, boolean activo) {
        tenantSupport.applyTenant(em);
        AdminUser objetivo = adminUserRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));

        if (objetivo.getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No puedes desactivarte a ti mismo");
        }
        if ("superadmin".equals(objetivo.getRol())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes modificar a un superadmin");
        }

        // UPDATE nativo dirigido, no repo.save(): la entidad AdminUser también mapea la
        // columna "rol" (enum nativo de Postgres) y Hibernate reescribe la fila completa al
        // hacer flush — el bind de "rol" como varchar sin CAST revienta aunque no haya cambiado
        // (mismo problema ya documentado en PedidoService para "ped_estado").
        em.createNativeQuery("UPDATE admin_users SET activo = :activo WHERE id = :id")
                .setParameter("activo", activo)
                .setParameter("id", id)
                .executeUpdate();
        em.clear();
        objetivo.setActivo(activo); // objetivo queda detached tras el clear(); esto solo actualiza la respuesta

        auditoriaService.registrar(currentTiendaId(), actor.getId(),
                activo ? "colaborador.activado" : "colaborador.desactivado", "admin_user", id, Map.of());

        return toResponse(objetivo);
    }

    /** excluirAdminId: al editar, no debe chocar consigo mismo si no cambió el documento. */
    private boolean existeDocumentoEnEmpleados(Long tiendaId, String tipoDocumento, String numeroDocumento,
                                                Long excluirAdminId) {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM empleados
                WHERE emp_tnd_id = :tiendaId AND emp_tipo_documento = CAST(:tipoDocumento AS tipo_documento)
                  AND emp_numero_documento = :numeroDocumento
                  AND (CAST(:excluirAdminId AS BIGINT) IS NULL OR emp_adm_id <> CAST(:excluirAdminId AS BIGINT))
                """)
                .setParameter("tiendaId", tiendaId)
                .setParameter("tipoDocumento", tipoDocumento)
                .setParameter("numeroDocumento", numeroDocumento)
                .setParameter("excluirAdminId", excluirAdminId)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private Long currentTiendaId() {
        String tndId = TenantContext.get();
        if (tndId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        }
        return Long.parseLong(tndId);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static AdminUserResponse toResponse(AdminUser a) {
        return new AdminUserResponse(a.getId(), a.getEmail(), a.getNombre(), a.getRol(), a.isActivo(),
                null, null, null, null, null, null);
    }
}
