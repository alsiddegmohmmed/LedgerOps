package com.ledgerops;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureRulesTests {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.ledgerops");
    }

    @Test
    void domainCodeHasNoFrameworkInfrastructureOrLoggingDependencies() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "org.slf4j.."
                )
                .check(productionClasses);
    }

    @Test
    void ledgerPublishedApiDoesNotExposeLedgerInternals() {
        classes()
                .that().resideInAPackage("com.ledgerops.ledger.api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.ledgerops.ledger.api..",
                        "java..",
                        "org.springframework.modulith.."
                )
                .check(productionClasses);
    }

    @Test
    void messagingPublishedApiDoesNotExposeMessagingInternals() {
        classes()
                .that().resideInAPackage("com.ledgerops.messaging.api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.ledgerops.messaging.api..",
                        "java..",
                        "org.springframework.modulith.."
                )
                .check(productionClasses);
    }

    @Test
    void providerDoesNotDependOnPaymentOrQueryItsPersistence() {
        noClasses()
                .that().resideInAPackage("com.ledgerops.provider..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ledgerops.payment..",
                        "org.springframework.web.client..",
                        "org.springframework.web.reactive.function.client.."
                )
                .check(productionClasses);
    }

    @Test
    void providerPublishedApiDoesNotExposeProviderInternals() {
        classes()
                .that().resideInAPackage("com.ledgerops.provider.api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.ledgerops.provider.api..",
                        "java..",
                        "org.springframework.modulith.."
                )
                .check(productionClasses);
    }

    @Test
    void controllersRemainInApiPackages() {
        classes()
                .that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..api..")
                .check(productionClasses);
    }

    @Test
    void productionCodeDoesNotUseFieldInjection() {
        fields()
                .should().notBeAnnotatedWith(Autowired.class)
                .check(productionClasses);
    }
}
