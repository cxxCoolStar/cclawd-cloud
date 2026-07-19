package ai.openagent.bootstrap.identity.controller;

import ai.openagent.bootstrap.identity.controller.request.AdminCreateUserRequest;
import ai.openagent.bootstrap.identity.controller.request.AdminResetPasswordRequest;
import ai.openagent.bootstrap.identity.controller.request.AdminUpdateUserRequest;
import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.service.UserAdminService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理控制器（V9 M2 RBAC）
 *
 * <p>
 * 契约对齐前端 api.ts 的 admin 系列函数：列表/创建/更新（角色、状态）/
 * 删除/重置密码。全部端点仅 super_admin 可用（服务层闸门，普通用户 403）
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    /**
     * 用户列表
     */
    @GetMapping("/api/users")
    public Map<String, List<CurrentUserVO.UserVO>> listUsers() {
        return Map.of("users", userAdminService.listUsers());
    }

    /**
     * 创建用户
     */
    @PostMapping("/api/users")
    public Map<String, Object> createUser(@RequestBody AdminCreateUserRequest request) {
        CurrentUserVO.UserVO user = userAdminService.createUser(
                request.username(),
                request.email(),
                request.password(),
                request.displayName(),
                request.role(),
                request.agentQuota());
        return Map.of("ok", true, "user", user);
    }

    /**
     * 更新用户（显示名/角色/状态/配额，null 字段不动）
     */
    @PutMapping("/api/users/{id}")
    public Map<String, Object> updateUser(@PathVariable String id, @RequestBody AdminUpdateUserRequest request) {
        CurrentUserVO.UserVO user = userAdminService.updateUser(
                id, request.displayName(), request.role(), request.status(), request.agentQuota());
        return Map.of("ok", true, "user", user);
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/api/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable String id) {
        userAdminService.deleteUser(id);
        return Map.of("ok", true);
    }

    /**
     * 重置用户密码（重置后该用户全部会话失效）
     */
    @PostMapping("/api/users/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable String id, @RequestBody AdminResetPasswordRequest request) {
        userAdminService.resetPassword(id, request.password());
        return Map.of("ok", true);
    }
}
