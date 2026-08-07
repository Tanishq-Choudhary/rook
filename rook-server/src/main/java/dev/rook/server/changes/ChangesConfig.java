package dev.rook.server.changes;

import dev.rook.changes.http.FetchPolicy;
import dev.rook.changes.http.SafeHttpFetcher;
import dev.rook.changes.source.GitHubReleaseNotes;
import dev.rook.changes.source.JdbcSnapshotStore;
import dev.rook.changes.source.MavenPoller;
import dev.rook.changes.source.OpenApiPoller;
import dev.rook.changes.source.ReleaseNotesProvider;
import dev.rook.changes.source.SnapshotStore;
import dev.rook.changes.source.SourcePoller;
import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.PollMetrics;
import dev.rook.changes.store.SourcePollService;
import dev.rook.changes.store.SourceRepository;
import dev.rook.core.change.SourceKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ChangesConfig {

    private static FetchPolicy policy(ChangesProperties properties) {
        FetchPolicy base = FetchPolicy.strict();
        return properties.allowLocalHttp() ? base.allowingLocalHttp() : base;
    }

    @Bean(destroyMethod = "close")
    SafeHttpFetcher metadataFetcher(ChangesProperties properties) {
        return new SafeHttpFetcher(policy(properties));
    }

    @Bean(destroyMethod = "close")
    SafeHttpFetcher artifactFetcher(ChangesProperties properties) {
        return new SafeHttpFetcher(policy(properties).withMaxBytes(properties.maxArtifactBytes()));
    }

    @Bean
    SnapshotStore snapshotStore(JdbcClient jdbc) {
        return new JdbcSnapshotStore(jdbc);
    }

    @Bean
    SourceRepository sourceRepository(JdbcClient jdbc) {
        return new SourceRepository(jdbc);
    }

    @Bean
    ChangeIngestion changeIngestion(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        return new ChangeIngestion(jdbc, tx, clock);
    }

    @Bean
    PollMetrics pollMetrics(MeterRegistry registry) {
        return new MicrometerPollMetrics(registry);
    }

    @Bean
    ReleaseNotesProvider releaseNotesProvider(
            SafeHttpFetcher metadataFetcher, ChangesProperties properties) {
        return new GitHubReleaseNotes(metadataFetcher, properties.githubApiBase());
    }

    @Bean
    SourcePoller openApiPoller(
            SafeHttpFetcher metadataFetcher, SnapshotStore snapshotStore, Clock clock) {
        return new OpenApiPoller(metadataFetcher, snapshotStore, clock);
    }

    @Bean
    SourcePoller mavenPoller(
            SafeHttpFetcher metadataFetcher,
            SafeHttpFetcher artifactFetcher,
            SnapshotStore snapshotStore,
            ReleaseNotesProvider releaseNotesProvider,
            Clock clock) {
        return new MavenPoller(
                metadataFetcher, artifactFetcher, snapshotStore, releaseNotesProvider, clock);
    }

    @Bean
    SourcePollService sourcePollService(
            SourceRepository sources,
            ChangeIngestion ingestion,
            SnapshotStore snapshotStore,
            List<SourcePoller> pollers,
            PollMetrics metrics,
            Clock clock) {
        Map<SourceKind, SourcePoller> byKind =
                pollers.stream().collect(Collectors.toMap(SourcePoller::kind, Function.identity()));
        return new SourcePollService(sources, ingestion, snapshotStore, byKind, metrics, clock);
    }
}
