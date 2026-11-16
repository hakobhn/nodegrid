package com.nodegrid;

import com.nodegrid.master.RegistrationRequest;
import com.nodegrid.master.WorkerView;
import com.nodegrid.node.NodeInfo;
import com.nodegrid.node.ResourceSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"nodegrid.role=master", "nodegrid.data-dir=target/test-data", "logging.file.name="})
class MasterNodeTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void exposesInfoAndResources() {
        NodeInfo info = rest.getForObject("/api/node/info", NodeInfo.class);
        assertThat(info.role().name()).isEqualTo("MASTER");

        ResourceSnapshot r = rest.getForObject("/api/node/resources", ResourceSnapshot.class);
        assertThat(r.availableProcessors()).isPositive();
        assertThat(r.totalMemoryBytes()).isPositive();
    }

    @Test
    void registeredWorkerAppearsInList() {
        rest.postForEntity("/api/master/workers/register",
                new RegistrationRequest("test-worker", "Test worker", "http://127.0.0.1:1"), Void.class);

        WorkerView[] workers = rest.getForObject("/api/master/workers", WorkerView[].class);
        assertThat(workers).extracting(WorkerView::id).contains("test-worker");

        rest.delete("/api/master/workers/test-worker");
    }
}
