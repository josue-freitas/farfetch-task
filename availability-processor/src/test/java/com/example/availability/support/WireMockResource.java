package com.example.availability.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Starts an in-process WireMock for the Quarkus tests using the SAME mappings and fixtures as the
 * provided {@code compose.yaml} ({@code ../wiremock}), with local response templating like the
 * container's {@code --local-response-templating}. Docker is therefore not needed to run the tests.
 */
public class WireMockResource implements QuarkusTestResourceLifecycleManager {

    private WireMockServer server;

    public static Path root() {
        Path root = Path.of(System.getProperty("wiremock.root", "../wiremock")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("mappings"))) {
            throw new IllegalStateException("WireMock mappings not found under " + root
                    + "; run the tests from the availability-processor folder of the exercise package");
        }
        return root;
    }

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options()
                .dynamicPort()
                .usingFilesUnderDirectory(root().toString())
                .templatingEnabled(true)
                .globalTemplating(false));
        server.start();
        String baseUrl = server.baseUrl();
        return Map.of(
                "quarkus.rest-client.product-context.url", baseUrl,
                "quarkus.rest-client.availability-api.url", baseUrl,
                "quarkus.rest-client.event-sink.url", baseUrl);
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(server,
                new TestInjector.AnnotatedAndMatchesType(InjectWireMock.class, WireMockServer.class));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
