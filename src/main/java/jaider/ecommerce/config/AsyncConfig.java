package jaider.ecommerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor dedicado para @Async. Sin este bean, Spring encuentra varios TaskExecutor ambiguos
 * ya registrados por la infraestructura de WebSocket (clientInboundChannelExecutor,
 * brokerChannelExecutor, etc.) y el canal lateral de notificaciones termina compitiendo por esos
 * mismos hilos internos del broker STOMP — hilos que el propio broker necesita para enrutar y
 * entregar los mensajes a las sesiones suscritas.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-notif-");
        executor.initialize();
        return executor;
    }
}
