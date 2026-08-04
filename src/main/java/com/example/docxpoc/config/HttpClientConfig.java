package com.example.docxpoc.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Pooled, timeout-bounded {@link RestTemplate} used for all calls to Gotenberg.
 *
 * <p>Spring's default {@code SimpleClientHttpRequestFactory} opens a new socket per
 * request and applies no timeouts. Both are fatal under load: sockets pile up in
 * TIME_WAIT until the ephemeral port range is exhausted, and a stalled conversion
 * blocks its caller thread forever instead of surfacing as a failed request.
 */
@Configuration
public class HttpClientConfig {

    /** Cap on total pooled connections. Should be >= the load test concurrency. */
    @Value("${loadtest.http.max-total:256}")
    private int maxTotal;

    /** Cap on connections to a single host. Gotenberg is one route, so this must match maxTotal. */
    @Value("${loadtest.http.max-per-route:256}")
    private int maxPerRoute;

    @Value("${loadtest.http.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds;

    /**
     * How long to wait for Gotenberg to respond. Must exceed Gotenberg's own
     * {@code --api-timeout}, otherwise the client gives up first and we lose the
     * server's error response.
     */
    @Value("${loadtest.http.response-timeout-seconds:600}")
    private int responseTimeoutSeconds;

    /** How long a thread waits for a free connection from the pool before failing. */
    @Value("${loadtest.http.connection-request-timeout-seconds:600}")
    private int connectionRequestTimeoutSeconds;

    @Bean
    public RestTemplate gotenbergRestTemplate() {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(connectTimeoutSeconds))
                .setSocketTimeout(Timeout.ofSeconds(responseTimeoutSeconds))
                .build();

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(connectionRequestTimeoutSeconds))
                .setResponseTimeout(Timeout.ofSeconds(responseTimeoutSeconds))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // Retries would silently inflate the conversion count and skew latency.
                .disableAutomaticRetries()
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
