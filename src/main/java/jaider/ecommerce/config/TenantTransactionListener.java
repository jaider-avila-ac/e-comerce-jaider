package jaider.ecommerce.config;

import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Al inicio de cada transacción Spring, inyecta:
 *   SET LOCAL app.current_tnd_id = '<id>'
 * en la conexión activa. Esto activa el Row Level Security de PostgreSQL.
 *
 * Se registra como TransactionSynchronization para ejecutarse
 * justo después de que la transacción abre la conexión.
 */
@Component
public class TenantTransactionListener {

    private final DataSource dataSource;

    public TenantTransactionListener(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void applyTenantToCurrentTransaction() {
        String tenantId = TenantContext.get();
        if (tenantId == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                // No-op: el SET LOCAL ya se hizo al inicio
            }
        });

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(
                "SET LOCAL app.current_tnd_id = '" + tenantId.replaceAll("[^0-9]", "") + "'"
            );
        } catch (Exception e) {
            // No interrumpir el flujo si falla el SET LOCAL
        }
    }
}
