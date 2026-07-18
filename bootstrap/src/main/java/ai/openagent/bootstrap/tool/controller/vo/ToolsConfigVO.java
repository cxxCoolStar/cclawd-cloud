package ai.openagent.bootstrap.tool.controller.vo;

import java.util.List;
import java.util.Map;

/**
 * 全局工具配置视图（GET /api/tools，前端 ToolsConfig 契约，V7 方案 3.4）
 *
 * <p>
 * V7 只交付空目录形状消除 404、页面优雅降级；完整 provider chain
 * （web_search/searxng 配置化）留 V8
 * </p>
 */
public record ToolsConfigVO(
        List<Object> categories, Map<String, Object> toolProviders, Map<String, Object> tools) {

    /**
     * 空目录形状（V7 占位）
     */
    public static ToolsConfigVO empty() {
        return new ToolsConfigVO(List.of(), Map.of(), Map.of());
    }
}
