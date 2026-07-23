package ai.openagent.bootstrap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
/**
 * 代码风格守护规则（参考 ragent 风格约定）
 *
 * <p>
 * 将 Phase 2 业务域分包 + Controller 改造确立的风格固化为可执行约束：
 * Controller 不碰持久层、业务代码不用 Web 层异常、Request/VO/ServiceImpl 命名规范。
 * 仅约束主代码，测试类不受命名规则限制
 * </p>
 */
@AnalyzeClasses(packages = "ai.openagent", importOptions = ImportOption.DoNotIncludeTests.class)
class CodeStyleArchitectureTest {

    /**
     * service 及以下不得使用 Web 层异常，业务代码应抛三层异常（Client/Service/Remote）
     */
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
    @ArchTest
    static final ArchRule controllers_do_not_access_persistence = noClasses()
            .that()
            .resideInAPackage("..controller")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..persistence..");

    /**
     * All Controller classes, including legacy root-package controllers, must not bypass
     * service boundaries through persistence or service implementation dependencies.
     */
    @ArchTest
    static final ArchRule controller_classes_do_not_depend_on_persistence_or_service_impl = noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..persistence..", "..service.impl..");
    /**
     * controller 包下的类命名必须以 Controller 结尾
     */
    @ArchTest
    static final ArchRule controller_classes_are_named_controller = classes()
            .that()
            .resideInAPackage("..controller")
            .should()
            .haveSimpleNameEndingWith("Controller");

    /**
     * controller/request 包下的类命名必须以 Request 结尾
     */
    @ArchTest
    static final ArchRule request_classes_are_named_request = classes()
            .that()
            .resideInAPackage("..controller.request..")
            .should()
            .haveSimpleNameEndingWith("Request");

    /**
     * controller/vo 包下的顶层类命名必须以 VO 结尾（匿名/内部类除外，
     * 如 Jackson TypeReference 匿名子类）
     */
    @ArchTest
    static final ArchRule vo_classes_are_named_vo = classes()
            .that()
            .resideInAPackage("..controller.vo..")
            .and()
            .areTopLevelClasses()
            .should()
            .haveSimpleNameEndingWith("VO");

    /**
     * service.impl 包下的顶层类命名必须以 ServiceImpl 结尾（匿名/内部类除外）
     */
    @ArchTest
    static final ArchRule service_impl_classes_are_named_service_impl = classes()
            .that()
            .resideInAPackage("..service.impl..")
            .and()
            .areTopLevelClasses()
            .should()
            .haveSimpleNameEndingWith("ServiceImpl");
    /**
     * dao.entity 包下的数据库实体沿用 ragent 的 DO 命名风格。
     */
    @ArchTest
    static final ArchRule dao_entities_are_named_do = classes()
            .that()
            .resideInAPackage("..dao.entity..")
            .and()
            .areTopLevelClasses()
            .should()
            .haveSimpleNameEndingWith("DO")
            .allowEmptyShould(true);

    /**
     * dao.mapper 包下的持久层接口沿用 ragent 的 Mapper 命名风格。
     */
    @ArchTest
    static final ArchRule dao_mappers_are_named_mapper = classes()
            .that()
            .resideInAPackage("..dao.mapper..")
            .and()
            .areTopLevelClasses()
            .should()
            .haveSimpleNameEndingWith("Mapper")
            .allowEmptyShould(true);

    /**
     * 新业务 Mapper 统一基于 MyBatis-Plus BaseMapper，避免继续新增手写 JDBC Repository 风格。
     */
    @ArchTest
    static final ArchRule dao_mappers_extend_base_mapper = classes()
            .that()
            .resideInAPackage("..dao.mapper..")
            .and()
            .areTopLevelClasses()
            .should()
            .beAssignableTo(BaseMapper.class)
            .allowEmptyShould(true);
    /**
     * 既有 Controller 的原始 Map 响应白名单。迁移接口时只允许从这里删除，
     * 新增 public Map 返回方法会被 controllers_do_not_add_new_map_returning_methods 拦住。
     */
    private static final Set<String> LEGACY_MAP_RETURNING_CONTROLLER_METHODS = Set.of();

    @Test
    void controllers_do_not_add_new_map_returning_methods() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ai.openagent");
        Set<String> newMapReturningMethods = new TreeSet<>();
        for (JavaMethod method : classes.stream()
                .filter(javaClass -> javaClass.getSimpleName().endsWith("Controller"))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .toList()) {
            if (!method.getModifiers().contains(JavaModifier.PUBLIC)
                    || !method.getRawReturnType().isEquivalentTo(Map.class)) {
                continue;
            }
            String signature = method.getOwner().getName() + "#" + method.getName();
            if (!LEGACY_MAP_RETURNING_CONTROLLER_METHODS.contains(signature)) {
                newMapReturningMethods.add(signature);
            }
        }
        assertThat(newMapReturningMethods)
                .as("New controller methods should return Result<T>/VO instead of raw Map; migrate deliberately and update the legacy whitelist downward.")
                .isEmpty();
    }
}
