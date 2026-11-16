package com.nodegrid.client;

import com.nodegrid.master.RegistrationRequest;
import com.nodegrid.node.NodeInfo;
import com.nodegrid.node.ResourceSnapshot;
import com.nodegrid.settings.SettingsService;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * HTTP client used for node-to-node communication (master → worker polling, worker → master registration).
 */
@Component
public class NodeClient {

    private final RestClient.Builder builder;
    private final SettingsService settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private volatile int cachedTimeout = -1;
    private volatile RestClient cachedClient;

    public NodeClient(RestClient.Builder builder, SettingsService settings) {
        this.builder = builder;
        this.settings = settings;
    }

    public NodeInfo fetchInfo(String baseUrl) {
        return client().get().uri(baseUrl + "/api/node/info")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(NodeInfo.class);
    }

    public ResourceSnapshot fetchResources(String baseUrl) {
        return client().get().uri(baseUrl + "/api/node/resources")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ResourceSnapshot.class);
    }

    public void register(String masterUrl, RegistrationRequest request) {
        client().post().uri(masterUrl + "/api/master/workers/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    /** Rebuilds the RestClient only when the configured timeout changes. */
    private RestClient client() {
        int timeout = settings.get().requestTimeoutSeconds();
        RestClient c = cachedClient;
        if (c == null || timeout != cachedTimeout) {
            synchronized (this) {
                if (cachedClient == null || timeout != cachedTimeout) {
                    var factory = new JdkClientHttpRequestFactory(http);
                    factory.setReadTimeout(Duration.ofSeconds(timeout));
                    cachedClient = builder.clone().requestFactory(factory).build();
                    cachedTimeout = timeout;
                }
                c = cachedClient;
            }
        }
        return c;
    }
}
