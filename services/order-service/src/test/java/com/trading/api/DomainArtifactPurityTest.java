package com.trading.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

public class DomainArtifactPurityTest {

    @Test
    public void embeddedDomainClassesHaveNoForbiddenTypeNames() throws Exception {
        Path domainRoot = Path.of("target", "classes", "com", "trading", "domain");
        if (!Files.isDirectory(domainRoot)) {
            fail("Embedded domain classes not found under target/classes/com/trading/domain");
        }

        List<String> forbidden = List.of(
                "org/springframework/",
                "org/mybatis/",
                "org/apache/ibatis/",
                "jakarta/servlet/",
                "javax/servlet/");
        List<String> matches = new ArrayList<>();

        try (Stream<Path> files = Files.walk(domainRoot)) {
            files.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                    .forEach(path -> scanClassFile(path, domainRoot, forbidden, matches));
        }

        if (!matches.isEmpty()) {
            fail("Embedded com.trading.domain classes reference forbidden package names: " + matches);
        }
    }

    private static void scanClassFile(Path classFile, Path domainRoot, List<String> forbidden, List<String> matches) {
        try {
            String bytecode = Files.readString(classFile, StandardCharsets.ISO_8859_1);
            for (String forbiddenName : forbidden) {
                if (bytecode.contains(forbiddenName)) {
                    matches.add(domainRoot.relativize(classFile) + " -> contains '" + forbiddenName + "'");
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to inspect " + classFile, ex);
        }
    }
}
