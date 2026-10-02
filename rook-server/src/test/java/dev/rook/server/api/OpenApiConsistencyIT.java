package dev.rook.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

@AutoConfigureMetrics
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rook.role=api"})
@Testcontainers
class OpenApiConsistencyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private static JsonNode document() throws IOException {
        return YAMLMapper.builder()
                .build()
                .readTree(Files.readString(Path.of("..", "docs", "openapi.yaml")));
    }

    private Set<String> implemented() {
        Set<String> operations = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mappings.getHandlerMethods().entrySet()) {
            Set<String> patterns =
                    entry.getKey().getPathPatternsCondition() == null
                            ? Set.of()
                            : entry.getKey().getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/v1")) {
                    continue;
                }
                for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                    operations.add(method.name().toLowerCase() + " " + pattern);
                }
            }
        }
        return operations;
    }

    private static Set<String> documented(JsonNode paths) {
        Set<String> operations = new TreeSet<>();
        for (String path : paths.propertyNames()) {
            if (!path.startsWith("/api/v1")) {
                continue;
            }
            Iterator<String> methods = paths.get(path).propertyNames().iterator();
            while (methods.hasNext()) {
                operations.add(methods.next() + " " + path);
            }
        }
        return operations;
    }

    @Test
    void everyImplementedEndpointIsDocumentedAndNothingElseIs() throws IOException {
        Set<String> documented = documented(document().get("paths"));
        Set<String> implemented = implemented();
        assertThat(implemented).isNotEmpty();
        assertThat(documented).isEqualTo(implemented);
    }

    @Test
    void everyOperationHasASummaryAndAtLeastOneResponse() throws IOException {
        JsonNode paths = document().get("paths");
        for (String path : paths.propertyNames()) {
            for (String method : paths.get(path).propertyNames()) {
                JsonNode operation = paths.get(path).get(method);
                assertThat(operation.path("summary").asString(""))
                        .as(method + " " + path)
                        .isNotBlank();
                assertThat(operation.path("responses").size()).as(method + " " + path).isPositive();
            }
        }
    }

    @Test
    void everyReferenceInTheDocumentPointsAtSomethingThatExists() throws IOException {
        JsonNode root = document();
        Set<String> references = new TreeSet<>();
        collect(root, references);
        assertThat(references).isNotEmpty();
        for (String reference : references) {
            JsonNode target = root;
            for (String part : reference.substring(2).split("/")) {
                target = target.path(part);
            }
            assertThat(target.isMissingNode()).as(reference).isFalse();
        }
    }

    private static void collect(JsonNode node, Set<String> references) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                JsonNode child = node.get(name);
                if (name.equals("$ref")) {
                    references.add(child.asString());
                } else {
                    collect(child, references);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, references));
        }
    }
}
