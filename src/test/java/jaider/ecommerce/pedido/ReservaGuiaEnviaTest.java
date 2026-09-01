package jaider.ecommerce.pedido;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corrección de auditoría (2026-09-01) — CRÍTICO: antes, generar una guía real leía el pedido,
 * comprobaba que {@code shipmentId == null}, y RECIÉN DESPUÉS llamaba a Envia y guardaba el
 * resultado. Dos solicitudes concurrentes (doble clic, dos pestañas del admin) podían pasar esa
 * comprobación antes de que cualquiera escribiera nada, generando y cobrando DOS guías reales
 * por el mismo pedido. Esta prueba cubre el mecanismo de reserva atómica que lo corrige —
 * ver {@link PedidoRepository#reservarParaGuiaEnvia} y {@code EnvioGuiaService.generarGuia}.
 */
@SpringBootTest
@Transactional
class ReservaGuiaEnviaTest {

    @Autowired
    private PedidoRepository pedidoRepo;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void reservar_soloUnaVez_laSegundaFalla() {
        Long pedId = crearPedidoDePrueba();

        int primera = pedidoRepo.reservarParaGuiaEnvia(pedId);
        assertThat(primera).isEqualTo(1);

        // Simula la segunda solicitud concurrente: llega DESPUÉS de que la primera ya reservó —
        // debe fallar (0 filas afectadas), nunca "ganar" una segunda vez.
        int segunda = pedidoRepo.reservarParaGuiaEnvia(pedId);
        assertThat(segunda).isEqualTo(0);
    }

    @Test
    void liberarReserva_permiteReintentarDespuesDeUnFalloDeEnvia() {
        Long pedId = crearPedidoDePrueba();

        assertThat(pedidoRepo.reservarParaGuiaEnvia(pedId)).isEqualTo(1);
        // Envia falló — se libera la reserva (mismo camino que el catch de generarGuia()).
        pedidoRepo.liberarReservaGuiaEnvia(pedId);

        // Ahora sí debe poder reintentar limpio.
        assertThat(pedidoRepo.reservarParaGuiaEnvia(pedId)).isEqualTo(1);
    }

    @Test
    void reservar_noAfectaUnPedidoQueYaTieneGuiaReal() {
        Long pedId = crearPedidoDePrueba();
        em.createNativeQuery("UPDATE pedidos SET ped_envia_shipment_id = 'YA-GENERADA' WHERE ped_id = :id")
                .setParameter("id", pedId).executeUpdate();

        assertThat(pedidoRepo.reservarParaGuiaEnvia(pedId)).isEqualTo(0);
    }

    private Long crearPedidoDePrueba() {
        TenantContext.set("1");
        tenantSupport.requireTenant(em);
        long unico = System.nanoTime();
        Number usrId = (Number) em.createNativeQuery("""
                SELECT usr_id FROM usuarios WHERE usr_tnd_id = 1 LIMIT 1
                """).getSingleResult();
        Number pedId = (Number) em.createNativeQuery("""
                INSERT INTO pedidos (ped_tnd_id, ped_usr_id, ped_numero, ped_dir_snapshot,
                                      ped_subtotal_centavos, ped_total_centavos, ped_estado)
                VALUES (1, :usrId, :numero, '{}', 10000000, 10000000, CAST('pagado' AS estado_pedido))
                RETURNING ped_id
                """)
                .setParameter("usrId", usrId)
                .setParameter("numero", "PRSV" + (Math.abs(unico) % 1_000_000_000L))
                .getSingleResult();
        return pedId.longValue();
    }
}
