package jaider.ecommerce.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter BOGOTA_FMT = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Bogota"));

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonDateCustomizer() {
        return builder -> builder.serializerByType(OffsetDateTime.class,
                new StdSerializer<>(OffsetDateTime.class) {
                    @Override
                    public void serialize(OffsetDateTime value, JsonGenerator gen,
                                         SerializerProvider provider) throws IOException {
                        gen.writeString(BOGOTA_FMT.format(value));
                    }
                });
    }
}
