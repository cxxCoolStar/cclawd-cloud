package ai.openagent.bootstrap.identity.controller;

import ai.openagent.bootstrap.identity.controller.request.RegistrationOpenRequest;
import ai.openagent.bootstrap.identity.service.RegistrationSettingsService;
import ai.openagent.bootstrap.identity.service.impl.UserAdminServiceImpl;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册开关管理控制器（V9 M2，/api/admin/registration）
 *
 * <p>
 * 契约对齐前端 getRegistration/setRegistration：{open: boolean}。
 * 仅 super_admin 可读写（普通用户 403）
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class RegistrationAdminController {

    private final RegistrationSettingsService registrationSettingsService;

    /**
     * 查询注册开关
     */
    @GetMapping("/api/admin/registration")
    public Map<String, Boolean> getRegistration() {
        UserAdminServiceImpl.requireSuperAdmin();
        return Map.of("open", registrationSettingsService.isOpen());
    }

    /**
     * 设置注册开关（立即生效并持久化）
     */
    @PutMapping("/api/admin/registration")
    public Map<String, Boolean> setRegistration(@RequestBody RegistrationOpenRequest request) {
        UserAdminServiceImpl.requireSuperAdmin();
        registrationSettingsService.setOpen(Boolean.TRUE.equals(request.open()));
        return Map.of("open", registrationSettingsService.isOpen());
    }
}
