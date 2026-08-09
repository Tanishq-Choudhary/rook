package dev.rook.codeintel.impact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PathTemplateTest {

    @Test
    void parametersMatchOneSegment() {
        PathTemplate template = new PathTemplate("/v1/charges/{id}");
        assertThat(template.matches("/v1/charges/ch_123")).isTrue();
        assertThat(template.matches("/v1/charges/{id}")).isTrue();
        assertThat(template.matches("/v1/charges")).isFalse();
        assertThat(template.matches("/v1/charges/a/b")).isFalse();
        assertThat(template.matches("/v2/charges/ch_1")).isFalse();
    }

    @Test
    void trailingSlashesAndQueryStringsAreTolerated() {
        PathTemplate template = new PathTemplate("/v1/charges");
        assertThat(template.matches("/v1/charges/")).isTrue();
        assertThat(template.matches("/v1/charges?limit=5")).isTrue();
        assertThat(template.matches("/v1/charges/extra")).isFalse();
    }

    @Test
    void staticPrefixesMatchConcatenatedPaths() {
        PathTemplate template = new PathTemplate("/v1/charges/{id}/refunds");
        assertThat(template.matchesPrefix("/v1/charges/")).isTrue();
        assertThat(template.matchesPrefix("/v1/charges/ch_1")).isTrue();
        assertThat(template.matchesPrefix("/v1/customers/")).isFalse();
    }

    @Test
    void literalPathsHaveNoPrefixMatches() {
        assertThat(new PathTemplate("/v1/charges").matchesPrefix("/v1/charges")).isFalse();
    }

    @Test
    void versionlessMatchesCoverBaseUrlStyles() {
        PathTemplate template = new PathTemplate("/v1/charges/{id}");
        assertThat(template.matchesWithoutVersion("/charges/ch_1")).isTrue();
        assertThat(template.matchesWithoutVersion("/customers/ch_1")).isFalse();
        assertThat(new PathTemplate("/charges").matchesWithoutVersion("/charges")).isFalse();
    }

    @Test
    void regexMetacharactersInPathsAreLiteral() {
        PathTemplate template = new PathTemplate("/v1/a.b+c");
        assertThat(template.matches("/v1/a.b+c")).isTrue();
        assertThat(template.matches("/v1/aXb+c")).isFalse();
    }
}
