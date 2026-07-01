package jaider.ecommerce.config;

import jaider.ecommerce.shared.interceptor.TenantContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

/**
 * Hibernate StatementInspector — no modifica las queries,
 * pero sirve como hook para documentar el patrón.
 *
 * El SET LOCAL se ejecuta vía JpaTransactionListener al abrir la tx.
 */
@Component
public class TenantJpaInterceptor implements StatementInspector {

    @Override
    public String inspect(String sql) {
        return sql;
    }

    public static String currentTenantId() {
        return TenantContext.get();
    }
}
