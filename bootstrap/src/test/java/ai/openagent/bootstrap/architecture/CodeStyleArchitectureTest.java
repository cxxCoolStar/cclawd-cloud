package ai.openagent.bootstrap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
