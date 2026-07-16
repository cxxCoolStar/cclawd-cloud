package ai.openagent.bootstrap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchIgnore;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 代码风格守护规则（参考 ragent 风格约定）
 *
 * <p>
 * 带 {@link ArchIgnore} 的规则为 Phase 2（业务域分包 + Controller 改造）完成后启用的占位规则，
 * 改造完成时移除注解使其生效
 * </p>
 */
@AnalyzeClasses(packages = "ai.openagent")
class CodeStyleArchitectureTest {

    /**
     * service 及以下不得使用 Web 层异常，业务代码应抛三层异常（Client/Service/Remote）
     */
    @ArchIgnore(reason = "Phase 2 Controller/Service 改造完成后启用")
    @ArchTest
    static final ArchRule services_do_not_throw_web_exceptions = noClasses()
            .that()
            .resideOutsideOfPackages("..controller..", "..web..")
            .and()
            .resideInAPackage("ai.openagent.bootstrap..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.server.ResponseStatusException");

    /**
     * Controller 不得直接依赖持久层，必须经由 Service
     */
    @ArchIgnore(reason = "Phase 2 补齐 Service 层后启用")
    @ArchTest
    static final ArchRule controllers_do_not_access_persistence = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..persistence..");

    /**
     * controller 包下的类命名必须以 Controller 结尾
     */
    @ArchIgnore(reason = "Phase 2 业务域分包完成后启用")
    @ArchTest
    static final ArchRule controller_classes_are_named_controller = classes()
            .that()
            .resideInAPackage("..controller")
            .should()
            .haveSimpleNameEndingWith("Controller");

    /**
     * controller/request 包下的类命名必须以 Request 结尾
     */
    @ArchIgnore(reason = "Phase 2 业务域分包完成后启用")
    @ArchTest
    static final ArchRule request_classes_are_named_request = classes()
            .that()
            .resideInAPackage("..controller.request..")
            .should()
            .haveSimpleNameEndingWith("Request");

    /**
     * controller/vo 包下的类命名必须以 VO 结尾
     */
    @ArchIgnore(reason = "Phase 2 业务域分包完成后启用")
    @ArchTest
    static final ArchRule vo_classes_are_named_vo = classes()
            .that()
            .resideInAPackage("..controller.vo..")
            .should()
            .haveSimpleNameEndingWith("VO");

    /**
     * service.impl 包下的类命名必须以 ServiceImpl 结尾
     */
    @ArchIgnore(reason = "Phase 2 业务域分包完成后启用")
    @ArchTest
    static final ArchRule service_impl_classes_are_named_service_impl = classes()
            .that()
            .resideInAPackage("..service.impl..")
            .should()
            .haveSimpleNameEndingWith("ServiceImpl");
}
