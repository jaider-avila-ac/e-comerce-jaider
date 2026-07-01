package jaider.ecommerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate usado por CatalogCacheService.
     * Spring Boot auto-configura la conexión desde spring.data.redis.*.
     * Spring Session usa su propio mecanismo (spring.session.redis.*).
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
