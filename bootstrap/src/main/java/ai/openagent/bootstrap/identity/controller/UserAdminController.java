package ai.openagent.bootstrap.identity.controller;

import ai.openagent.bootstrap.identity.controller.request.AdminCreateUserRequest;
import ai.openagent.bootstrap.identity.controller.request.AdminResetPasswordRequest;
import ai.openagent.bootstrap.identity.controller.request.AdminUpdateUserRequest;
import ai.openagent.bootstrap.identity.controller.vo.UserListVO;
import ai.openagent.bootstrap.identity.controller.vo.UserMutationVO;
import ai.openagent.bootstrap.identity.service.UserManagementService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
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

    private final UserManagementService userManagementService;

    /**
     * 用户列表
     */
    @GetMapping("/api/users")
    public Result<UserListVO> listUsers() {
        return Results.success(userManagementService.list());
    }

    /**
     * 创建用户
     */
    @PostMapping("/api/users")
    public Result<UserMutationVO> createUser(@RequestBody AdminCreateUserRequest request) {
        return Results.success(userManagementService.create(request));
    }

    /**
     * 更新用户（显示名/角色/状态/配额，null 字段不动）
     */
    @PutMapping("/api/users/{id}")
    public Result<UserMutationVO> updateUser(@PathVariable String id, @RequestBody AdminUpdateUserRequest request) {
        return Results.success(userManagementService.update(id, request));
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/api/users/{id}")
    public Result<Void> deleteUser(@PathVariable String id) {
        userManagementService.delete(id);
        return Results.success();
    }

    /**
     * 重置用户密码（重置后该用户全部会话失效）
     */
    @PostMapping("/api/users/{id}/password")
    public Result<Void> resetPassword(@PathVariable String id, @RequestBody AdminResetPasswordRequest request) {
        userManagementService.resetPassword(id, request);
        return Results.success();
    }
}
