package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class TechStackDetector {

    private static final Logger log = LoggerFactory.getLogger(TechStackDetector.class);

    public List<String> detect(ScmProvider provider, ScmConnectionConfig config, ScmRepository repo) {
        List<String> stack = new ArrayList<>();

        Optional<String> pomXml = safeGetFile(provider, config, repo, "pom.xml");
        if (pomXml.isPresent()) {
            addIfAbsent(stack, "java");
            if (pomXml.get().contains("spring-boot")) {
                addIfAbsent(stack, "spring-boot");
            }
        }

        if (!stack.contains("java")) {
            Optional<String> buildGradle = safeGetFile(provider, config, repo, "build.gradle")
                    .or(() -> safeGetFile(provider, config, repo, "build.gradle.kts"));
            if (buildGradle.isPresent()) {
                addIfAbsent(stack, "java");
                if (buildGradle.get().contains("spring-boot")) {
                    addIfAbsent(stack, "spring-boot");
                }
            }
        }

        safeGetFile(provider, config, repo, "package.json")
                .ifPresent(_ -> addIfAbsent(stack, "javascript"));

        safeGetFile(provider, config, repo, "go.mod")
                .ifPresent(_ -> addIfAbsent(stack, "go"));

        safeGetFile(provider, config, repo, "Cargo.toml")
                .ifPresent(_ -> addIfAbsent(stack, "rust"));

        safeGetFile(provider, config, repo, "requirements.txt")
                .ifPresent(_ -> addIfAbsent(stack, "python"));

        safeGetFile(provider, config, repo, "Dockerfile")
                .ifPresent(content -> detectFromDockerfile(content, stack));

        return List.copyOf(stack);
    }

    private void detectFromDockerfile(String content, List<String> stack) {
        content.lines()
                .filter(line -> line.trim().toUpperCase().startsWith("FROM"))
                .forEach(line -> {
                    String lower = line.toLowerCase();
                    if (!stack.contains("java") && lower.contains("eclipse-temurin")) addIfAbsent(stack, "java");
                    if (lower.contains("node:")) addIfAbsent(stack, "javascript");
                    if (lower.contains("python:")) addIfAbsent(stack, "python");
                    if (lower.contains("golang:")) addIfAbsent(stack, "go");
                    if (lower.contains("rust:")) addIfAbsent(stack, "rust");
                });
    }

    private Optional<String> safeGetFile(ScmProvider provider, ScmConnectionConfig config,
                                          ScmRepository repo, String filePath) {
        try {
            return provider.getFileContents(config, repo.fullPath(), filePath);
        } catch (Exception e) {
            log.debug("Could not fetch {} from {}: {}", filePath, repo.fullPath(), e.getMessage());
            return Optional.empty();
        }
    }

    private static void addIfAbsent(List<String> list, String value) {
        if (!list.contains(value)) list.add(value);
    }
}
