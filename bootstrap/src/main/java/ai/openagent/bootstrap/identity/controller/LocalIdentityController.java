package ai.openagent.bootstrap.identity.controller;

import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.service.IdentityService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地身份控制器
 * 提供当前用户查询与登出接口（本地单用户模式，无真实认证）
 */
@RestController
@RequiredArgsConstructor
public class LocalIdentityController {

    private final IdentityService identityService;

    /**
     * 查询当前用户身份
     */
    @GetMapping("/api/me")
    public CurrentUserVO me() {
        return identityService.currentUser();
    }

    /**
     * 登出（本地模式为空操作，保持 fastclaw 协议兼容）
     */
    @PostMapping("/api/logout")
    public Map<String, Object> logout() {
        return Map.of("ok", true);
    }
}
