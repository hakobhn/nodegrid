package com.nodegrid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NodeGridApplication {

    public static void main(String[] args) {
        fixUnixDomainSocketDir();
        SpringApplication.run(NodeGridApplication.class, args);
    }

    /** AF_UNIX socket paths are limited to ~108 chars; leave room for the JDK's generated file name. */
    private static final int MAX_SOCKET_DIR_LENGTH = 80;

    /**
     * On Windows the JDK builds NIO pipes (used by every Selector: Tomcat, the HTTP client) on AF_UNIX sockets
     * created in %TEMP%. On some machines (e.g. with endpoint-security software) connecting to sockets in %TEMP%
     * fails with "Unable to establish loopback connection" and the app can't start. Using a directory owned by the
     * app ({@code ./data/tmp}) avoids that. An explicit {@code -Djdk.net.unixdomain.tmpdir} always wins.
     * Must run before any NIO class is used.
     */
    static void fixUnixDomainSocketDir() {
        if (System.getProperty("jdk.net.unixdomain.tmpdir") != null
                || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            Path dir = Path.of(System.getProperty("user.dir"), "data", "tmp").toAbsolutePath();
            if (dir.toString().length() <= MAX_SOCKET_DIR_LENGTH) {
                Files.createDirectories(dir);
                System.setProperty("jdk.net.unixdomain.tmpdir", dir.toString());
            }
        } catch (Exception ignored) {
            // keep the JDK default
        }
    }
}
