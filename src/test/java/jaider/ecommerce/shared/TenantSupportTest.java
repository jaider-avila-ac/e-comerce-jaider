package jaider.ecommerce.shared;

import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test puro (sin Spring, sin BD) para las dos variantes de TenantSupport, §3.3 del plan:
 * "toda operación que acceda a información de negocio debe requerir tenant... no continuar
 * silenciosamente sin aplicarlo".
 *
 * Antes de la corrección (2026-08-30), applyTenant() era la ÚNICA variante y devolvía
 * silenciosamente cuando no había tenant en contexto — un servicio tenantizado con un bug de
 * resolución de tenant (dominio no registrado, header ausente, etc.) simplemente ejecutaba su
 * consulta bajo RLS sin ningún tenant fijado y recibía una lista vacía, indistinguible de "esta
 * tienda no tiene datos". requireTenant() cierra ese hueco fallando con 400 explícito;
 * applyTenant() se queda, a propósito, solo para login/me de admin_users (sirve también al
 * superadmin, que nunca tiene tenant por diseño).
 */
class TenantSupportTest {

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void requireTenant_sinContexto_lanza400YNoTocaLaBD() {
        TenantContext.clear();
        EntityManager em = mock(EntityManager.class);
        TenantSupport support = new TenantSupport();

        assertThatThrownBy(() -> support.requireTenant(em))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(em, never()).createNativeQuery(anyString());
    }

    @Test
    void requireTenant_contextoEnBlanco_tambienLanza400() {
        TenantContext.set("   ");
        EntityManager em = mock(EntityManager.class);
        TenantSupport support = new TenantSupport();

        assertThatThrownBy(() -> support.requireTenant(em))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requireTenant_conContexto_fijaElTenantEnLaSesionDePostgres() {
        TenantContext.set("1");
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("id"), eq("1"))).thenReturn(query);
        TenantSupport support = new TenantSupport();

        support.requireTenant(em);

        verify(query).getSingleResult();
    }

    @Test
    void applyTenant_sinContexto_esPermisivoYNoLanzaNada() {
        // A propósito: login/me de admin_users usa esta variante porque sirve tanto a un admin
        // de tienda (SÍ necesita tenant) como al superadmin (NUNCA tiene tenant, por diseño).
        TenantContext.clear();
        EntityManager em = mock(EntityManager.class);
        TenantSupport support = new TenantSupport();

        support.applyTenant(em);

        verify(em, never()).createNativeQuery(anyString());
    }
}
