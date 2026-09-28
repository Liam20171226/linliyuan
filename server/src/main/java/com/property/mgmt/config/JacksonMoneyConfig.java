package com.property.mgmt.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额统一序列化为两位小数（如 185 → 185.00），保证前后端展示口径一致。
 */
@Configuration
public class JacksonMoneyConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalScale2() {
        return builder -> builder.serializerByType(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                if (value == null) {
                    gen.writeNull();
                    return;
                }
                gen.writeNumber(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        });
    }
}
