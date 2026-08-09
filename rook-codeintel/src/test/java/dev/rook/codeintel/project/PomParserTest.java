package dev.rook.codeintel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import org.junit.jupiter.api.Test;

class PomParserTest {

    private static final String POM =
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>3.1.0</version>
              </parent>
              <artifactId>service</artifactId>
              <properties>
                <acme.version>2.4.1</acme.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.stripe</groupId>
                    <artifactId>stripe-java</artifactId>
                    <version>${acme.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-sdk</artifactId>
                  <version>${acme.version}</version>
                </dependency>
                <dependency>
                  <groupId>com.stripe</groupId>
                  <artifactId>stripe-java</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>${project.version}</version>
                  <scope>test</scope>
                </dependency>
                <dependency>
                  <groupId>com.unknown</groupId>
                  <artifactId>no-version</artifactId>
                </dependency>
                <dependency>
                  <groupId>com.unknown</groupId>
                  <artifactId>bad-property</artifactId>
                  <version>${does.not.exist}</version>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void inheritsGroupAndVersionFromTheParent() {
        PomParser.Pom pom = PomParser.parse(POM, "pom.xml");
        assertThat(pom.groupId()).isEqualTo("com.example");
        assertThat(pom.version()).isEqualTo("3.1.0");
        assertThat(pom.artifactId()).isEqualTo("service");
    }

    @Test
    void resolvesPropertyVersions() {
        PomParser.Pom pom = PomParser.parse(POM, "pom.xml");
        Dependency acme =
                pom.dependencies().stream()
                        .filter(d -> d.artifactId().equals("acme-sdk"))
                        .findFirst()
                        .orElseThrow();
        assertThat(acme.version()).isEqualTo("2.4.1");
        assertThat(acme.scope()).isEqualTo("compile");
        assertThat(acme.declaredIn()).isEqualTo("pom.xml");
        assertThat(acme.coordinates()).isEqualTo("com.acme:acme-sdk");
    }

    @Test
    void usesDependencyManagementWhenTheVersionIsMissing() {
        PomParser.Pom pom = PomParser.parse(POM, "pom.xml");
        Dependency stripe =
                pom.dependencies().stream()
                        .filter(d -> d.artifactId().equals("stripe-java"))
                        .findFirst()
                        .orElseThrow();
        assertThat(stripe.version()).isEqualTo("2.4.1");
    }

    @Test
    void projectVersionPlaceholdersAndScopesAreHonoured() {
        PomParser.Pom pom = PomParser.parse(POM, "pom.xml");
        Dependency junit =
                pom.dependencies().stream()
                        .filter(d -> d.artifactId().equals("junit-jupiter"))
                        .findFirst()
                        .orElseThrow();
        assertThat(junit.version()).isEqualTo("3.1.0");
        assertThat(junit.scope()).isEqualTo("test");
    }

    @Test
    void unresolvableVersionsBecomeNullInsteadOfLeakingPlaceholders() {
        PomParser.Pom pom = PomParser.parse(POM, "pom.xml");
        assertThat(pom.dependencies())
                .filteredOn(d -> d.groupId().equals("com.unknown"))
                .allSatisfy(d -> assertThat(d.version()).isNull());
    }

    @Test
    void doctypeDeclarationsAreRefused() {
        String hostile =
                "<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project><artifactId>&x;</artifactId></project>";
        assertThatThrownBy(() -> PomParser.parse(hostile, "pom.xml"))
                .isInstanceOf(RookException.class);
    }
}
