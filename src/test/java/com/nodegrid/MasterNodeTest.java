package com.nodegrid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodegrid.command.Command;
import com.nodegrid.command.CommandResult;
import com.nodegrid.command.CommandStatus;
import com.nodegrid.master.CommandQueue;
import com.nodegrid.master.RegistrationRequest;
import com.nodegrid.master.WorkerStatus;
import com.nodegrid.master.WorkerView;
import com.nodegrid.node.NodeInfo;
import com.nodegrid.node.ResourceSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"nodegrid.role=master", "nodegrid.data-dir=target/test-data", "logging.file.name="})
class MasterNodeTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Test
    void exposesInfoAndResources() {
        NodeInfo info = rest.getForObject("/api/node/info", NodeInfo.class);
        assertThat(info.role().name()).isEqualTo("MASTER");

        ResourceSnapshot r = rest.getForObject("/api/node/resources", ResourceSnapshot.class);
        assertThat(r.availableProcessors()).isPositive();
        assertThat(r.totalMemoryBytes()).isPositive();
    }

    @Test
    void heartbeatRegistersWorkerWithResources() {
        ResourceSnapshot snapshot = rest.getForObject("/api/node/resources", ResourceSnapshot.class);
        rest.postForEntity("/api/master/workers/register",
                new RegistrationRequest("test-worker", "Test worker", "", 15, snapshot, List.of("ping")), Void.class);

        WorkerView w = rest.getForObject("/api/master/workers/test-worker", WorkerView.class);
        assertThat(w.status()).isEqualTo(WorkerStatus.ONLINE);
        assertThat(w.resources().availableProcessors()).isEqualTo(snapshot.availableProcessors());
        assertThat(w.commandTypes()).containsExactly("ping");

        rest.delete("/api/master/workers/test-worker");
    }

    @Test
    void commandRoundTripOverWorkerLongPoll() {
        rest.postForEntity("/api/master/workers/register",
                new RegistrationRequest("cmd-worker", "Cmd worker", "", 15, null, List.of("ping")), Void.class);

        // unsupported type is rejected up front
        var rejected = rest.postForEntity("/api/master/workers/cmd-worker/commands",
                Map.of("type", "format-disk"), String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // master queues a command
        CommandQueue.CommandView queued = rest.postForObject("/api/master/workers/cmd-worker/commands",
                Map.of("type", "ping", "payload", Map.of("message", "hi")), CommandQueue.CommandView.class);
        assertThat(queued.status()).isEqualTo(CommandStatus.QUEUED);

        // worker long-poll picks it up
        Command[] delivered = rest.getForObject("/api/master/workers/cmd-worker/commands/next?wait=2", Command[].class);
        assertThat(delivered).extracting(Command::id).containsExactly(queued.id());
        assertThat(delivered[0].payload().get("message").asText()).isEqualTo("hi");

        // worker reports the result
        JsonNode out = mapper.valueToTree(Map.of("pong", true));
        rest.postForEntity("/api/master/workers/cmd-worker/commands/" + queued.id() + "/result",
                new CommandResult(true, out, null), Void.class);

        CommandQueue.CommandView done = rest.getForObject("/api/master/commands/" + queued.id(),
                CommandQueue.CommandView.class);
        assertThat(done.status()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(done.result().get("pong").asBoolean()).isTrue();

        // nothing left: an empty long-poll returns after the wait
        Command[] empty = rest.getForObject("/api/master/workers/cmd-worker/commands/next?wait=0", Command[].class);
        assertThat(empty).isEmpty();

        rest.delete("/api/master/workers/cmd-worker");
    }
}
