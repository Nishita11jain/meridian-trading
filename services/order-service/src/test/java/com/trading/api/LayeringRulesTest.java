package com.trading.api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

// Enforces the Task 3 layering acceptance criteria by name, matching manifest.env.
// ArchUnit 1.3.0's bytecode importer cannot parse Java 25 class files yet, so
// this rule set can't run on a JDK 25 toolchain -- it still runs on the CI/team
// JDKs this project otherwise targets. Skipping rather than failing avoids a
// false negative that has nothing to do with the layering itself.
@DisabledOnJre(JRE.JAVA_25)
public class LayeringRulesTest {

    private static final String CONTROLLER_PACKAGE = "com.trading.api.controller..";
    private static final String SERVICE_PACKAGE = "com.trading.api.service..";
    private static final String MAPPER_PACKAGE = "com.trading.api.mapper..";

    // The rules describe the boundaries of the production code, so test classes are not imported:
    // a test in the service package may legitimately use web types (for example to assert the
    // HTTP status the advice maps an exception to) without breaking the layering.
    private static final JavaClasses APP_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(location -> !location.toString().startsWith("jrt:"))
            .importPackages("com.trading.api");

    @Test
    void controllersDoNotDependOnMapperPackage() {
        ArchRule rule = noClasses().that().resideInAPackage(CONTROLLER_PACKAGE)
                .should().dependOnClassesThat().resideInAPackage(MAPPER_PACKAGE);
        rule.check(APP_CLASSES);
    }

    @Test
    void controllersDoNotDependOnSqlOrPersistenceApis() {
        ArchRule rule = noClasses().that().resideInAPackage(CONTROLLER_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.sql..", "javax.sql..",
                        "org.apache.ibatis..", "org.mybatis..",
                        "org.springframework.jdbc..",
                        "jakarta.persistence..", "javax.persistence..");
        rule.check(APP_CLASSES);
    }

    @Test
    void controllersDoNotOpenTransactions() {
        ArchRule rule = noClasses().that().resideInAPackage(CONTROLLER_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.transaction..");
        rule.check(APP_CLASSES);
    }

    @Test
    void servicesDoNotDependOnServletTypes() {
        ArchRule rule = noClasses().that().resideInAPackage(SERVICE_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "javax.servlet..");
        rule.check(APP_CLASSES);
    }

    @Test
    void servicesDoNotDependOnHttpStatusOrWebTypes() {
        ArchRule rule = noClasses().that().resideInAPackage(SERVICE_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.http..", "org.springframework.web..");
        rule.check(APP_CLASSES);
    }
}
