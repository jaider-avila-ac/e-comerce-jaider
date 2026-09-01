package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corrección de auditoría (2026-09-01, tercera vuelta) — CRÍTICO: antes, la reserva, la llamada
 * real a Envia y el registro final vivían en LA MISMA transacción de
 * {@code EnvioGuiaService.generarGuia()} — si el commit final fallaba, Postgres revertía TODO,
 * incluida la reserva, dejando el pedido como si nunca se hubiera generado nada aunque Envia ya
 * hubiera cobrado de verdad (un reintento generaba una SEGUNDA guía real).
 *
 * Esta prueba NO usa @Transactional a nivel de clase a propósito: {@link EnvioGuiaTransaccionesService}
 * usa REQUIRES_NEW, que solo tiene un efecto observable real si cada paso commitea de verdad en
 * su propia transacción — envolver el test entero en una transacción que se revierte sola
 * escondería justo el comportamiento que hay que probar (y además, el pedido de prueba no sería
 * visible para la transacción REQUIRES_NEW mientras la del test siga sin commitear). Se usa
 * TransactionTemplate para crear/borrar el fixture en transacciones cortas y ya comprometidas,
 * y se limpia manualmente en @AfterEach (nada de esto se revierte solo).
 */
@SpringBootTest
class EnvioGuiaTransaccionesServiceTest {

    @Autowired
    private EnvioGuiaTransaccionesService transacciones;
    @Autowired
    private TenantSupport tenantSupport;
    @Autowired
    private PlatformTransactionManager txManager;
    @PersistenceContext
    private EntityManager em;

    private final List<Long> pedidosCreados = new ArrayList<>();

    @AfterEach
    void limpiar() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            TenantContext.set("1");
            tenantSupport.requireTenant(em);
            for (Long id : pedidosCreados) {
                em.createNativeQuery("DELETE FROM pedidos WHERE ped_id = :id").setParameter("id", id).executeUpdate();
            }
        });
        TenantContext.clear();
    }

    @Test
    void confirmarShipmentId_bloqueaPermanentementeUnaSegundaReserva_auncuandoElRegistroDetalleNuncaCorre() {
        TenantContext.set("1");
        Long pedId = crearPedidoDePruebaComprometido();

        // Paso 1: reservar — commitea en su propia transacción (REQUIRES_NEW).
        assertThat(transacciones.reservar(pedId)).isEqualTo(1);

        // Paso 2 (el crítico): Envia ya confirmó y cobró — se persiste el shipmentId REAL,
        // también en su propia transacción, ya comprometida ANTES de que este método retorne.
        assertThat(transacciones.confirmarShipmentIdTx(pedId, "TEST-SHIP-REAL-001")).isEqualTo(1);

        // Verificación directa en la BD (transacción NUEVA, no la del método de arriba): el
        // shipmentId real ya quedó persistido de forma durable, sin depender de ningún paso
        // posterior.
        String shipmentIdPersistido = leerShipmentId(pedId);
        assertThat(shipmentIdPersistido).isEqualTo("TEST-SHIP-REAL-001");

        // Paso 3 NUNCA ocurre acá a propósito (simula que registrarDetalleGuia falló o el
        // proceso murió antes de llamarlo) — el punto de esta prueba es que NO IMPORTA: el
        // pedido ya no puede volver a reservarse, así que no puede generarse una guía duplicada.
        assertThat(transacciones.reservar(pedId)).isEqualTo(0);
    }

    @Test
    void reservar_esAtomicoEIndependienteDeLlamadasPosteriores() {
        TenantContext.set("1");
        Long pedId = crearPedidoDePruebaComprometido();

        assertThat(transacciones.reservar(pedId)).isEqualTo(1);
        // Segunda reserva mientras sigue en 'RESERVANDO' (Envia todavía "procesando") — debe fallar.
        assertThat(transacciones.reservar(pedId)).isEqualTo(0);

        // Si Envia falla, se libera — en su propia transacción, comprometida de inmediato.
        transacciones.liberarReserva(pedId);
        assertThat(leerShipmentId(pedId)).isNull();

        // Ahora sí puede reintentarse limpio.
        assertThat(transacciones.reservar(pedId)).isEqualTo(1);
    }

    private String leerShipmentId(Long pedId) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(status -> {
            TenantContext.set("1");
            tenantSupport.requireTenant(em);
            return (String) em.createNativeQuery("SELECT ped_envia_shipment_id FROM pedidos WHERE ped_id = :id")
                    .setParameter("id", pedId)
                    .getSingleResult();
        });
    }

    private Long crearPedidoDePruebaComprometido() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long pedId = tx.execute(status -> {
            TenantContext.set("1");
            tenantSupport.requireTenant(em);
            long unico = System.nanoTime();
            Number usrId = (Number) em.createNativeQuery("""
                    SELECT usr_id FROM usuarios WHERE usr_tnd_id = 1 LIMIT 1
                    """).getSingleResult();
            Number id = (Number) em.createNativeQuery("""
                    INSERT INTO pedidos (ped_tnd_id, ped_usr_id, ped_numero, ped_dir_snapshot,
                                          ped_subtotal_centavos, ped_total_centavos, ped_estado)
                    VALUES (1, :usrId, :numero, '{}', 10000000, 10000000, CAST('pagado' AS estado_pedido))
                    RETURNING ped_id
                    """)
                    .setParameter("usrId", usrId)
                    .setParameter("numero", "PTXN" + (Math.abs(unico) % 1_000_000_000L))
                    .getSingleResult();
            return id.longValue();
        });
        pedidosCreados.add(pedId);
        return pedId;
    }
}
