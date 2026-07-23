package ai.openagent.bootstrap.identity.controller;

import ai.openagent.bootstrap.identity.controller.request.ChangePasswordRequest;
import ai.openagent.bootstrap.identity.controller.request.UpdateMeRequest;
import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.service.IdentityService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份控制器
 * 当前用户查询与自助资料维护（显示名 / 头像 / 密码）
 */
@RestController
@RequiredArgsConstructor
public class IdentityController {

    private final IdentityService identityService;

    /**
     * 查询当前用户身份
     */
    @GetMapping("/api/me")
    public CurrentUserVO me() {
        return identityService.currentUser();
    }

    /**
     * 更新当前用户显示名与头像
     */
    @PutMapping("/api/me")
    public CurrentUserVO updateMe(@RequestBody @Valid UpdateMeRequest request) {
        return identityService.updateMe(request.displayName(), request.avatarUrl());
    }

    /**
     * 修改当前用户密码（校验旧密码）
     */
    @PostMapping("/api/me/password")
    public Result<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        identityService.changePassword(request.oldPassword(), request.newPassword());
        return Results.success();
    }
}
