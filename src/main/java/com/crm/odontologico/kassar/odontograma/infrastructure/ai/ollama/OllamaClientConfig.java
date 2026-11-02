package com.crm.odontologico.kassar.odontograma.infrastructure.ai.ollama;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuración del cliente HTTP síncrono ({@link RestClient}) dedicado a la comunicación con Ollama.
 */
@Configuration
@ConditionalOnProperty(prefix = "odontograma.ai", name = "provider", havingValue = "ollama")
@EnableConfigurationProperties(OllamaProperties.class)
public class OllamaClientConfig {

    @Bean
    public RestClient ollamaRestClient(OllamaProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
