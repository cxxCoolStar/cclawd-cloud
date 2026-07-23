package ai.openagent.bootstrap.identity.controller;

import ai.openagent.bootstrap.identity.controller.request.CreateApiKeyRequest;
import ai.openagent.bootstrap.identity.controller.vo.ApiKeyListVO;
import ai.openagent.bootstrap.identity.controller.vo.CreatedApiKeyVO;
import ai.openagent.bootstrap.identity.service.ApiKeyManagementService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * API Key 控制器（V9 M2）
 *
 * <p>
 * 契约对齐前端 apikeys 页：列表 {apikeys:[...]}（key 打码）；创建响应
 * {apikey, token}，明文 token 仅此一次返回；删除按属主隔离（他人 Key
 * 一律 404）。用户只能管理自己的 Key
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyManagementService apiKeyManagementService;

    /**
     * 当前用户的 Key 列表
     */
    @GetMapping("/api/apikeys")
    public Result<ApiKeyListVO> listApikeys() {
        return Results.success(apiKeyManagementService.list());
    }

    /**
     * 创建 Key：{apikey, token}，token 为仅此一次返回的明文
     */
    @PostMapping("/api/apikeys")
    public Result<CreatedApiKeyVO> createApikey(@RequestBody CreateApiKeyRequest request) {
        return Results.success(apiKeyManagementService.create(request));
    }

    /**
     * 删除 Key
     */
    @DeleteMapping("/api/apikeys/{id}")
    public Result<Void> deleteApikey(@PathVariable String id) {
        apiKeyManagementService.delete(id);
        return Results.success();
    }
}
