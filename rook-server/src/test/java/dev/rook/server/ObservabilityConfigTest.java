package dev.rook.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

class ObservabilityConfigTest {

    private static final Path ROOT = Path.of("..");
    private static final Path OBSERVABILITY = ROOT.resolve("deploy").resolve("observability");
    private static final Pattern METER =
            Pattern.compile("(?:builder|counter|gauge|timer)\\(\\s*\"(rook\\.[a-z.]+)\"");
    private static final Pattern USED = Pattern.compile("\\brook_[a-z_]+");
    private static final Set<String> SUFFIXES =
            Set.of("", "_total", "_seconds", "_seconds_bucket", "_seconds_count", "_seconds_sum");

    private static Set<String> registered() throws IOException {
        Set<String> names = new HashSet<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".java"))
                            .filter(
                                    f ->
                                            f.toString()
                                                    .contains(
                                                            "src"
                                                                    + java.io.File.separator
                                                                    + "main"))
                            .toList()) {
                Matcher matcher = METER.matcher(Files.readString(file));
                while (matcher.find()) {
                    names.add(matcher.group(1).replace('.', '_'));
                }
            }
        }
        return names;
    }

    private static boolean known(String used, Set<String> registered) {
        for (String suffix : SUFFIXES) {
            if (used.endsWith(suffix)
                    && registered.contains(used.substring(0, used.length() - suffix.length()))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> metricsIn(String text) {
        Set<String> found = new HashSet<>();
        Matcher matcher = USED.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void theCodeRegistersTheMetersTheConfigsNeed() throws IOException {
        assertThat(registered())
                .contains(
                        "rook_jobs", "rook_job_steps", "rook_outbox_pending", "rook_http_rejected");
    }

    @Test
    void everyAlertReadsAMetricTheCodeRegisters() throws IOException {
        Set<String> registered = registered();
        String text = Files.readString(OBSERVABILITY.resolve("alerts.yml"));
        assertThat(metricsIn(text)).isNotEmpty().allMatch(name -> known(name, registered));
    }

    @Test
    void everyDashboardQueryReadsAMetricTheCodeRegisters() throws IOException {
        Set<String> registered = registered();
        String text =
                Files.readString(
                        OBSERVABILITY
                                .resolve("grafana")
                                .resolve("dashboards")
                                .resolve("rook-overview.json"));
        assertThat(metricsIn(text)).isNotEmpty().allMatch(name -> known(name, registered));
    }

    @Test
    void everyAlertHasASeverityAndASummary() throws IOException {
        JsonNode document =
                YAMLMapper.builder()
                        .build()
                        .readTree(Files.readString(OBSERVABILITY.resolve("alerts.yml")));
        Set<String> names = new HashSet<>();
        int rules = 0;
        for (JsonNode group : document.get("groups")) {
            for (JsonNode rule : group.get("rules")) {
                rules++;
                names.add(rule.get("alert").asString());
                assertThat(rule.path("labels").path("severity").asString()).isNotBlank();
                assertThat(rule.path("annotations").path("summary").asString()).isNotBlank();
                assertThat(rule.path("expr").asString()).isNotBlank();
            }
        }
        assertThat(rules).isGreaterThanOrEqualTo(8);
        assertThat(names).hasSize(rules);
    }

    @Test
    void theDashboardIsValidAndItsPanelsAreDistinct() throws IOException {
        JsonNode dashboard =
                new ObjectMapper()
                        .readTree(
                                Files.readString(
                                        OBSERVABILITY
                                                .resolve("grafana")
                                                .resolve("dashboards")
                                                .resolve("rook-overview.json")));
        Set<Integer> ids = new HashSet<>();
        for (JsonNode panel : dashboard.get("panels")) {
            assertThat(ids.add(panel.get("id").asInt())).isTrue();
            assertThat(panel.get("title").asString()).isNotBlank();
            assertThat(panel.get("targets")).isNotEmpty();
            assertThat(panel.get("datasource").get("uid").asString()).isEqualTo("prometheus");
        }
        assertThat(ids).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void everyProvisionedConfigIsValidYaml() throws IOException {
        YAMLMapper mapper = YAMLMapper.builder().build();
        try (Stream<Path> files = Files.walk(ROOT.resolve(".github"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                assertThat(mapper.readTree(Files.readString(file))).as(file.toString()).isNotNull();
            }
        }
        try (Stream<Path> files = Files.walk(ROOT.resolve("deploy"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                assertThat(mapper.readTree(Files.readString(file))).as(file.toString()).isNotNull();
            }
        }
        JsonNode datasources =
                mapper.readTree(
                        Files.readString(
                                OBSERVABILITY
                                        .resolve("grafana")
                                        .resolve("provisioning")
                                        .resolve("datasources")
                                        .resolve("datasources.yml")));
        Set<String> uids = new HashSet<>();
        for (JsonNode source : datasources.get("datasources")) {
            uids.add(source.get("uid").asString());
        }
        assertThat(uids).containsExactlyInAnyOrder("prometheus", "loki", "tempo");
    }

    @Test
    void prometheusLoadsTheAlertFileTheComposeFileMounts() throws IOException {
        String prometheus = Files.readString(OBSERVABILITY.resolve("prometheus.yml"));
        String compose =
                Files.readString(
                        ROOT.resolve("deploy").resolve("compose").resolve("observability.yml"));
        assertThat(prometheus).contains("/etc/prometheus/alerts.yml");
        assertThat(compose).contains(":/etc/prometheus/alerts.yml");
        assertThat(prometheus).contains("/actuator/prometheus");
    }
}
