package ai.openagent.bootstrap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "ai.openagent")
class ModuleArchitectureTest {

    @ArchTest
    static final ArchRule framework_is_independent = noClasses()
            .that()
            .resideInAPackage("ai.openagent.framework..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "ai.openagent.infra..",
                    "ai.openagent.agent..",
                    "ai.openagent.runtime..",
                    "ai.openagent.bootstrap..");

    @ArchTest
    static final ArchRule ai_infrastructure_does_not_depend_on_agent_or_bootstrap = noClasses()
            .that()
            .resideInAPackage("ai.openagent.infra.ai..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("ai.openagent.agent..", "ai.openagent.runtime..", "ai.openagent.bootstrap..");

    @ArchTest
    static final ArchRule agent_core_does_not_depend_on_runtime_or_bootstrap = noClasses()
            .that()
            .resideInAPackage("ai.openagent.agent..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("ai.openagent.runtime..", "ai.openagent.bootstrap..");
}

