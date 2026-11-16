package com.hospital.opd.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    /**
     * Real network timeouts. block(timeout) in the integration services only stops waiting;
     * these make Netty abandon the connection too.
     */
    private static ReactorClientHttpConnector connector(int connectTimeoutMs, Duration responseTimeout) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(responseTimeout);
        return new ReactorClientHttpConnector(http);
    }

    /** Calls to the lab service. The caller supplies the Authorization header (user or service token). */
    @Bean
    public WebClient labWebClient(@Value("${lab.service.url}") String labServiceUrl,
                                  @Value("${app.http.connect-timeout-ms:2000}") int connectTimeoutMs,
                                  @Value("${app.http.response-timeout:4s}") Duration responseTimeout) {
        return WebClient.builder()
                .baseUrl(labServiceUrl)
                .clientConnector(connector(connectTimeoutMs, responseTimeout))
                .build();
    }

    /** Internal auth lookups authenticate with the shared cluster secret (never exposed via the gateway). */
    @Bean
    public WebClient internalAuthWebClient(@Value("${auth.service.url}") String authServiceUrl,
                                           @Value("${app.internal-secret}") String internalSecret,
                                           @Value("${app.http.connect-timeout-ms:2000}") int connectTimeoutMs,
                                           @Value("${app.http.response-timeout:4s}") Duration responseTimeout) {
        return WebClient.builder()
                .baseUrl(authServiceUrl)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .clientConnector(connector(connectTimeoutMs, responseTimeout))
                .build();
    }
}
