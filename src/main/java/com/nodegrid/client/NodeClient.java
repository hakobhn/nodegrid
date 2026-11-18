package com.nodegrid.client;

import com.nodegrid.command.Command;
import com.nodegrid.command.CommandResult;
import com.nodegrid.master.RegistrationRequest;
import com.nodegrid.settings.SettingsService;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * HTTP client for node-to-node communication. Every call goes worker → master: heartbeats, the command long-poll
 * and command results. The master never opens a connection to a worker.
 */
@Component
public class NodeClient {

    /** How long the master may hold a command long-poll open. */
    public static final int LONG_POLL_SECONDS = 25;

    private final RestClient.Builder builder;
    private final SettingsService settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private final RestClient longPollClient;

    private volatile int cachedTimeout = -1;
    private volatile RestClient cachedClient;

    public NodeClient(RestClient.Builder builder, SettingsService settings) {
        this.builder = builder;
        this.settings = settings;
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(LONG_POLL_SECONDS + 15));
        this.longPollClient = builder.clone().requestFactory(factory).build();
    }

    public void register(String masterUrl, RegistrationRequest request) {
        client().post().uri(masterUrl + "/api/master/workers/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    /** Long-poll: returns as soon as the master has commands for this worker, or empty after ~25s. */
    public List<Command> nextCommands(String masterUrl, String workerId) {
        List<Command> list = longPollClient.get()
                .uri(masterUrl + "/api/master/workers/" + enc(workerId) + "/commands/next?wait=" + LONG_POLL_SECONDS)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Command>>() { });
        return list == null ? List.of() : list;
    }

    public void reportResult(String masterUrl, String workerId, String commandId, CommandResult result) {
        client().post()
                .uri(masterUrl + "/api/master/workers/" + enc(workerId) + "/commands/" + enc(commandId) + "/result")
                .contentType(MediaType.APPLICATION_JSON)
                .body(result)
                .retrieve()
                .toBodilessEntity();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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
