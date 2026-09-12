package dev.rook.eval.generate;

import java.util.LinkedHashMap;
import java.util.Map;

final class CustomerCode {

    static final String MAIN = "src/main/java/app/";
    static final String TEST = "src/test/java/app/";

    private static final String POM =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>app</groupId>
              <artifactId>${artifact}</artifactId>
              <version>1.0.0</version>
              <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>6.0.3</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.0</version>
                  </plugin>
                  <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.4</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private static final String FIELD_SERVICE =
            """
            package app;

            ${imports}
            public class Service {

                private final ${client} client;

                public Service(${client} client) {
                    this.client = client;
                }

                public String single(String key) {
                    return ${single};
                }

                public String batch(String[] keys) {
                    StringBuilder out = new StringBuilder();
                    for (String key : keys) {
                        out.append(${single}).append(';');
                    }
                    return out.toString();
                }
            }
            """;

    private static final String LOCAL_SERVICE =
            """
            package app;

            ${imports}
            public class Service {

                public String single(String key) {
                    ${client} client = new ${client}();
                    return ${single};
                }

                public String batch(String[] keys) {
                    ${client} client = new ${client}();
                    StringBuilder out = new StringBuilder();
                    for (String key : keys) {
                        out.append(${single}).append(';');
                    }
                    return out.toString();
                }
            }
            """;

    private static final String FIELD_ARCHIVE =
            """
            package app;

            ${imports}
            public class Archive {

                private final ${client} client = new ${client}();

                public String once(String key) {
                    return ${single};
                }
            }
            """;

    private static final String LOCAL_ARCHIVE =
            """
            package app;

            ${imports}
            public class Archive {

                public String once(String key) {
                    ${client} archive = new ${client}();
                    return ${archived};
                }
            }
            """;

    private static final String FIELD_WIRING =
            """
            package app;

            ${clientImport}
            public class Wiring {

                public static Service service() {
                    return new Service(new ${client}());
                }
            }
            """;

    private static final String LOCAL_WIRING =
            """
            package app;

            public class Wiring {

                public static Service service() {
                    return new Service();
                }
            }
            """;

    private static final String SERVICE_TEST =
            """
            package app;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class ServiceTest {

                @Test
                void singleCallUsesTheKey() {
                    assertEquals("${tag}:a", Wiring.service().single("a"));
                }

                @Test
                void batchJoinsEveryResult() {
                    assertEquals("${tag}:a;${tag}:b;", Wiring.service().batch(new String[] {"a", "b"}));
                }
            }
            """;

    private static final String CONTRACT_TEST =
            """
            package app;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class ServiceContractTest {

                @Test
                void archiveReturnsTheTaggedKey() {
                    assertEquals("${tag}:doc-7", new Archive().once("doc-7"));
                }

                @Test
                void anEmptyBatchIsEmpty() {
                    assertEquals("", Wiring.service().batch(new String[0]));
                }

                @Test
                void serviceAndArchiveAgree() {
                    assertEquals(Wiring.service().single("k"), new Archive().once("k"));
                }
            }
            """;

    private CustomerCode() {}

    static Map<String, String> repo(String id, Domain domain, Style style, Usage usage) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("artifact", id);
        values.put("client", domain.client());
        values.put("tag", domain.tag());
        values.put("imports", usage.imports());
        values.put("clientImport", "import " + usage.clientImport() + ";\n");
        values.put("single", usage.call("client", "key"));
        values.put("archived", usage.call("archive", "key"));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", Template.render(POM, values));
        boolean field = style == Style.FIELD;
        files.put(
                MAIN + "Service.java",
                Template.render(field ? FIELD_SERVICE : LOCAL_SERVICE, values));
        files.put(
                MAIN + "Archive.java",
                Template.render(field ? FIELD_ARCHIVE : LOCAL_ARCHIVE, values));
        files.put(
                MAIN + "Wiring.java", Template.render(field ? FIELD_WIRING : LOCAL_WIRING, values));
        files.put(TEST + "ServiceTest.java", Template.render(SERVICE_TEST, values));
        return files;
    }

    static Map<String, String> verify(Domain domain) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                TEST + "ServiceContractTest.java",
                Template.render(CONTRACT_TEST, Map.of("tag", domain.tag())));
        return files;
    }
}
