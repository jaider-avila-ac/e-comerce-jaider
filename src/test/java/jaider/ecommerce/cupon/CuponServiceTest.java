package jaider.ecommerce.cupon;

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
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Garantías centrales de los cupones ("bono de compra", PENDIENTE_DUO_CHIC):
 *   - código aleatorio y personalizado funcionan, y el personalizado se normaliza a mayúsculas;
 *   - no se puede repetir un mismo código en la misma tienda;
 *   - validar() nunca consume un uso (solo informa);
 *   - canjear() sí consume, de forma atómica: el segundo canje de un cupón de un solo uso falla. */
@SpringBootTest
@Transactional
class CuponServiceTest {

    @Autowired
    private CuponService service;

    @Autowired
    private SuperadminTiendaService superadminService;

    @PersistenceContext
    private EntityManager em;

    /** Devuelve el EMAIL (no el id): crear()/cambiarActivo() lo resuelven a id ellos mismos,
     *  DENTRO de su propia transacción (ver CuponService.adminIdDe) — pasar el id ya resuelto
     *  saltaba justo la parte que probaba el bug real (RLS + SET LOCAL fuera de transacción). */
    private String crearAdminAuditor() {
        String email = "cupon-test-" + System.nanoTime() + "@example.com";
        em.createNativeQuery("""
                INSERT INTO admin_users (email, password, nombre, rol, tienda_id, activo)
                VALUES (:email, 'x', 'Admin Test', CAST('superadmin' AS rol_empleado), NULL, true)
                """)
                .setParameter("email", email)
                .executeUpdate();
        return email;
    }

    private Long crearTienda() {
        long unico = System.nanoTime();
        TenantProvisioningResult creada = superadminService.crearBorrador(new CrearTiendaRequest(
                "Tienda Cupon Test", "Tienda Cupon Test SAS", "NIT-" + unico, "tienda-cupon-" + unico,
                "tienda-cupon-" + unico + ".test", "contacto" + unico + "@example.com", null, null,
                "admin" + unico + "@example.com", "ContraseñaSegura123", "Admin Prueba",
                null, null, null, null
        ));
        return creada.tenantId();
    }

    @Test
    void crear_generaCodigoAleatorioUnico_yNormalizaElPersonalizadoAMayusculas() {
        Long tndId = crearTienda();
        TenantContext.set(tndId.toString());
        String adminEmail = crearAdminAuditor();
        try {
            CuponResponse aleatorio = service.crear(tndId,
                    new CrearCuponRequest(null, true, "porcentaje", 10L, 1), adminEmail);
            assertThat(aleatorio.codigo()).hasSize(8);

            CuponResponse personalizado = service.crear(tndId,
                    new CrearCuponRequest("verano20", false, "fijo", 5000L, 1), adminEmail);
            assertThat(personalizado.codigo()).isEqualTo("VERANO20");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void crear_rechazaCodigoDuplicadoEnLaMismaTienda() {
        Long tndId = crearTienda();
        TenantContext.set(tndId.toString());
        String adminEmail = crearAdminAuditor();
        try {
            service.crear(tndId, new CrearCuponRequest("REPETIDO", false, "porcentaje", 10L, 1), adminEmail);
            assertThatThrownBy(() ->
                    service.crear(tndId, new CrearCuponRequest("repetido", false, "porcentaje", 10L, 1), adminEmail))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Ya existe");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void validar_calculaElDescuentoSinConsumirNingunUso() {
        Long tndId = crearTienda();
        TenantContext.set(tndId.toString());
        String adminEmail = crearAdminAuditor();
        try {
            CuponResponse c = service.crear(tndId,
                    new CrearCuponRequest("DESC10", false, "porcentaje", 10L, 1), adminEmail);

            ValidarCuponResponse v = service.validar(tndId, "desc10", 100_000L); // 1000 pesos = 100000 centavos
            assertThat(v.valido()).isTrue();
            assertThat(v.descuentoCentavos()).isEqualTo(10_000L); // 10% de 100000

            // Validar no gasta el uso — sigue en 0/1.
            CuponResponse recargado = service.listar(tndId).stream()
                    .filter(x -> x.id().equals(c.id())).findFirst().orElseThrow();
            assertThat(recargado.usosActuales()).isZero();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void canjear_consumeUnUso_yElSegundoCanjeDeUnoDeUnSoloUsoFalla() {
        Long tndId = crearTienda();
        TenantContext.set(tndId.toString());
        String adminEmail = crearAdminAuditor();
        try {
            service.crear(tndId, new CrearCuponRequest("SOLOUNO", false, "fijo", 2000L, 1), adminEmail);

            Cupon primero = service.canjear(tndId, "soloUno");
            assertThat(primero.getUsosActuales()).isEqualTo(1);

            assertThatThrownBy(() -> service.canjear(tndId, "SOLOUNO"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("ya no está disponible");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void canjear_conLimiteDeUsos_dejaCanjearHastaElLimiteYLuegoFalla() {
        Long tndId = crearTienda();
        TenantContext.set(tndId.toString());
        String adminEmail = crearAdminAuditor();
        try {
            service.crear(tndId, new CrearCuponRequest("TRESUSOS", false, "porcentaje", 5L, 3), adminEmail);

            service.canjear(tndId, "TRESUSOS");
            service.canjear(tndId, "TRESUSOS");
            Cupon tercero = service.canjear(tndId, "TRESUSOS");
            assertThat(tercero.getUsosActuales()).isEqualTo(3);

            assertThatThrownBy(() -> service.canjear(tndId, "TRESUSOS"))
                    .isInstanceOf(ResponseStatusException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cambiarActivo_desactivaUnCuponYValidarLoRechaza() {
        Long tndId = crearTienda();
        TenantContext.set(tndId.toString());
        String adminEmail = crearAdminAuditor();
        try {
            CuponResponse c = service.crear(tndId,
                    new CrearCuponRequest("DESACTIVAR", false, "porcentaje", 10L, 5), adminEmail);

            service.cambiarActivo(tndId, c.id(), false, adminEmail);

            ValidarCuponResponse v = service.validar(tndId, "DESACTIVAR", 50_000L);
            assertThat(v.valido()).isFalse();
            assertThat(v.motivo()).contains("ya no está activo");
        } finally {
            TenantContext.clear();
        }
    }
}
