package io.cartogra.ingestion;

import io.cartogra.ingestion.application.port.out.CommitInfo;
import io.cartogra.ingestion.application.port.out.OwnershipMap;
import io.cartogra.ingestion.application.port.out.ScmConnectionConfig;
import io.cartogra.ingestion.application.port.out.ScmProvider;
import io.cartogra.ingestion.application.port.out.ScmRepository;
import io.cartogra.ingestion.application.usecase.TechStackDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TechStackDetectorTest {

    private TechStackDetector detector;
    private ScmConnectionConfig config;
    private ScmRepository repo;

    @BeforeEach
    void setUp() {
        detector = new TechStackDetector();
        config = new ScmConnectionConfig(UUID.randomUUID(), UUID.randomUUID(), "github", Map.of());
        repo = new ScmRepository("1", "test-repo", "org/test-repo", "main", false, null, null);
    }

    @Test
    void detectsJavaFromPomXml() {
        ScmProvider provider = stubProvider(Map.of("pom.xml", "<project/>"));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).containsExactly("java");
    }

    @Test
    void detectsJavaAndSpringBootFromPomXml() {
        ScmProvider provider = stubProvider(Map.of("pom.xml", "<dependency><artifactId>spring-boot-starter-web</artifactId></dependency>"));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).containsExactlyInAnyOrder("java", "spring-boot");
    }

    @Test
    void detectsJavascriptFromPackageJson() {
        ScmProvider provider = stubProvider(Map.of("package.json", "{}"));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).containsExactly("javascript");
    }

    @Test
    void detectsGoFromGoMod() {
        ScmProvider provider = stubProvider(Map.of("go.mod", "module example.com/app"));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).containsExactly("go");
    }

    @Test
    void detectsRustFromCargoToml() {
        ScmProvider provider = stubProvider(Map.of("Cargo.toml", "[package]"));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).containsExactly("rust");
    }

    @Test
    void detectsPythonFromRequirementsTxt() {
        ScmProvider provider = stubProvider(Map.of("requirements.txt", "flask==2.0.0"));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).containsExactly("python");
    }

    @Test
    void detectsJavaFromDockerfileEclipseTemurin() {
        ScmProvider provider = stubProvider(Map.of("Dockerfile", "FROM eclipse-temurin:25-jre-jammy"));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).containsExactly("java");
    }

    @Test
    void detectsJavascriptFromDockerfileNode() {
        ScmProvider provider = stubProvider(Map.of("Dockerfile", "FROM node:20-alpine"));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).containsExactly("javascript");
    }

    @Test
    void doesNotDuplicateJavaWhenPomAndDockerfile() {
        ScmProvider provider = stubProvider(Map.of(
                "pom.xml", "<project/>",
                "Dockerfile", "FROM eclipse-temurin:25-jre-jammy"
        ));
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result.stream().filter("java"::equals).count()).isEqualTo(1);
    }

    @Test
    void returnsEmptyListForRepoWithNoMatchingFiles() {
        ScmProvider provider = stubProvider(Map.of());
        List<String> result = detector.detect(provider, config, repo);
        assertThat(result).isEmpty();
    }

    private static ScmProvider stubProvider(Map<String, String> files) {
        return new ScmProvider() {
            @Override
            public String providerType() { return "stub"; }

            @Override
            public List<ScmRepository> listRepositories(ScmConnectionConfig cfg) { return List.of(); }

            @Override
            public Optional<String> getFileContents(ScmConnectionConfig cfg, String repoPath, String filePath) {
                return Optional.ofNullable(files.get(filePath));
            }

            @Override
            public Optional<CommitInfo> getLastCommit(ScmConnectionConfig cfg, ScmRepository r) {
                return Optional.empty();
            }

            @Override
            public OwnershipMap resolveOwnership(ScmConnectionConfig cfg, ScmRepository r) {
                return new OwnershipMap(List.of(), Map.of());
            }
        };
    }
}
