package com.example.fast2smsmockgateway.config;

import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenApiCustomizer sandboxContract() throws IOException {
        var spec = new ClassPathResource("openapi/fast2sms-sandbox.yaml");
        OpenAPI contract;
        try (var stream = spec.getInputStream()) {
            contract = Yaml31.mapper().readValue(stream, OpenAPI.class);
        }

        return generated -> {
            generated.setInfo(contract.getInfo());
            generated.setServers(contract.getServers());
            generated.setTags(contract.getTags());
            generated.setExternalDocs(contract.getExternalDocs());
            generated.setSecurity(contract.getSecurity());
            generated.setComponents(contract.getComponents());
            generated.setPaths(contract.getPaths());
        };
    }
}
