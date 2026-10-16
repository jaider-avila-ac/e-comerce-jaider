package jaider.ecommerce.auditoria;

import jaider.ecommerce.shared.dto.PageResponse;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningResult;
import jaider.ecommerce.tienda.superadmin.CrearTiendaRequest;
import jaider.ecommerce.tienda.superadmin.SuperadminTiendaService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garantía explícita del usuario (2026-10-15): el admin de una tienda NUNCA debe ver en su
 * propia auditoría qué hizo el superadmin de la plataforma sobre esa tienda — solo lo que hizo
 * su propio equipo (admin/colaborador/bodega).
 */
@SpringBootTest
@Transactional
class AuditoriaServiceTest {

    @Autowired
    private AuditoriaService service;

    @Autowired
    private SuperadminTiendaService superadminService;

    @PersistenceContext
    private EntityManager em;

    @Test
    void listar_excluyeAccionesDelSuperadminPeroIncluyeLasDelPropioAdmin() {
        long unico = System.nanoTime();
        Long superadminId = ((Number) em.createNativeQuery("""
                INSERT INTO admin_users (email, password, nombre, rol, tienda_id, activo)
                VALUES (:email, 'x', 'Superadmin Test', CAST('superadmin' AS rol_empleado), NULL, true)
                RETURNING id
                """)
                .setParameter("email", "auditoria-superadmin-" + unico + "@example.com")
                .getSingleResult()).longValue();

        TenantProvisioningResult creada = superadminService.crearBorrador(new CrearTiendaRequest(
                "Tienda Auditoria Test", "Tienda Auditoria Test SAS", "NIT-" + unico, "tienda-auditoria-" + unico,
                "tienda-auditoria-" + unico + ".test", "contacto" + unico + "@example.com", null, null,
                "admin" + unico + "@example.com", "ContraseñaSegura123", "Admin Prueba",
                null, null, null, null
        ));
        Long tndId = creada.tenantId();
        Long adminId = ((Number) em.createNativeQuery(
                "SELECT id FROM admin_users WHERE email = :email")
                .setParameter("email", "admin" + unico + "@example.com")
                .getSingleResult()).longValue();

        TenantContext.set(tndId.toString());
        try {
            service.registrar(tndId, superadminId, "superadmin.activada", "tienda", tndId, Map.of());
            service.registrar(tndId, adminId, "tienda_admin.wompi_actualizado", "tienda_secretos", tndId, Map.of());

            // Limpia el contexto de persistencia: sin esto, la consulta nativa de abajo devuelve
            // las MISMAS instancias ya gestionadas por este EntityManager en vez de leer de la
            // BD — y esas instancias nunca tuvieron "creadoEn" poblado (columna con default de
            // BD, insertable=false), lo que rompe el formateo de fecha en listar() con un NPE
            // que no pasaría en producción (ahí registrar/listar son transacciones distintas).
            em.flush();
            em.clear();

            PageResponse<AuditoriaResponse> pagina = service.listar(tndId, null, 0, 20);

            assertThat(pagina.content()).extracting(AuditoriaResponse::accion)
                    .contains("tienda_admin.wompi_actualizado")
                    .doesNotContain("superadmin.activada");
        } finally {
            TenantContext.clear();
        }
    }
}
