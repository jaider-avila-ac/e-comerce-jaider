package jaider.ecommerce.auditoria;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.dto.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registro de auditoría para acciones administrativas sensibles (quién hizo qué). */
@Service
@RequiredArgsConstructor
public class AuditoriaService {

    private final AuditoriaRepository repository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    private static final DateTimeFormatter FECHA_BOGOTA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Bogota"));

    @Transactional
    public void registrar(Long tndId, Long adminId, String accion, String entidad, Long entidadId,
                           Map<String, Object> detalle) {
        tenantSupport.applyTenant(em);

        Auditoria auditoria = new Auditoria();
        auditoria.setTndId(tndId);
        auditoria.setAdminId(adminId);
        auditoria.setAccion(accion);
        auditoria.setEntidad(entidad);
        auditoria.setEntidadId(entidadId);
        auditoria.setDetalle(detalle != null ? detalle : Map.of());
        repository.save(auditoria);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditoriaResponse> listar(Long tndId, String entidad, int page, int size) {
        tenantSupport.applyTenant(em);

        Page<Auditoria> resultado = (entidad != null && !entidad.isBlank())
                ? repository.findByTndIdAndEntidadOrderByCreadoEnDesc(tndId, entidad, PageRequest.of(page, size))
                : repository.findByTndIdOrderByCreadoEnDesc(tndId, PageRequest.of(page, size));

        Set<Long> adminIds = resultado.getContent().stream().map(Auditoria::getAdminId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, String[]> admins = cargarAdmins(adminIds);

        List<AuditoriaResponse> content = resultado.getContent().stream()
                .map(a -> {
                    String[] admin = admins.getOrDefault(a.getAdminId(), new String[]{"—", ""});
                    return new AuditoriaResponse(a.getId(), admin[0], admin[1], a.getAccion(), a.getEntidad(),
                            a.getEntidadId(), a.getDetalle(), FECHA_BOGOTA.format(a.getCreadoEn()));
                })
                .toList();

        return new PageResponse<>(content, resultado.getNumber(), resultado.getSize(),
                resultado.getTotalElements(), resultado.getTotalPages());
    }

    @SuppressWarnings("unchecked")
    private Map<Long, String[]> cargarAdmins(Set<Long> adminIds) {
        if (adminIds.isEmpty()) return Map.of();
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, nombre, email FROM admin_users WHERE id IN :ids")
                .setParameter("ids", adminIds)
                .getResultList();

        Map<Long, String[]> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put(((Number) row[0]).longValue(), new String[]{(String) row[1], (String) row[2]});
        }
        return result;
    }
}
