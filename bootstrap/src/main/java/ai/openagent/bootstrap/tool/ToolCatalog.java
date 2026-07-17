package ai.openagent.bootstrap.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内置工具目录（V2 方案 3.1 首批内置工具）
 *
 * <p>
 * 工具承载位置决策（V2 方案 20.4 决策 1）：采用方案 B —— 内置工具归属
 * bootstrap 的 tool 域，不恢复 runtime-integration 模块；工具实现（M4）
 * 落在 {@code tool/adapter/} 下实现 agent-core 端口。
 * 本目录是"平台支持哪些工具、默认是否启用"的唯一事实来源：
 * DataSeeder 据此为默认 Agent 写入 agent_tools 种子，ToolRegistry（M3）
 * 据此校验工具名白名单
 * </p>
 *
 * @param name           工具名（模型可见的调用名）
 * @param description    一句话说明
 * @param riskLevel      风险级别
 * @param enabledDefault 是否默认启用（写入/网络类工具默认禁用，需显式开启）
 */
public record ToolCatalog(String name, String description, RiskLevel riskLevel, boolean enabledDefault) {

    /**
     * 工具风险级别
     */
    public enum RiskLevel {
        /**
         * 只读低风险（时间、计算）
         */
        LOW,
        /**
         * 只读中风险（workspace 文件读取）
         */
        MEDIUM,
        /**
         * 写入或网络访问，须显式启用
         */
        HIGH
    }

    /**
     * 首批内置工具清单（V2 方案 3.1 表格的代码化，2026-07-17 修订：
     * 对齐 fastclaw 核心文件工具族，砍掉自创的 get_current_time/calculator，
     * 补入 edit_file）
     */
    public static final List<ToolCatalog> BUILTIN_TOOLS = List.of(
            new ToolCatalog("list_dir", "列出 Agent workspace 内的文件和目录", RiskLevel.MEDIUM, true),
            new ToolCatalog("read_file", "读取 workspace 内 UTF-8 文本文件", RiskLevel.MEDIUM, true),
            new ToolCatalog("write_file", "在 workspace 内创建或覆盖文本文件", RiskLevel.HIGH, false),
            new ToolCatalog("edit_file", "按精确子串替换编辑 workspace 内文本文件", RiskLevel.HIGH, false),
            new ToolCatalog("apply_patch", "对 workspace 内文本文件应用受限补丁", RiskLevel.HIGH, false),
            new ToolCatalog("web_fetch", "获取经过安全校验的 HTTP/HTTPS 文本资源", RiskLevel.HIGH, false),
            new ToolCatalog("memory_search", "检索 Agent 长期记忆文件（MEMORY.md/USER.md/HISTORY.md）", RiskLevel.MEDIUM, true),
            new ToolCatalog("exec", "在 Docker 沙箱容器内执行 shell 命令", RiskLevel.HIGH, false));

    private static final Map<String, ToolCatalog> BY_NAME =
            BUILTIN_TOOLS.stream().collect(Collectors.toUnmodifiableMap(ToolCatalog::name, Function.identity()));

    /**
     * 按名称查询内置工具（工具名白名单校验入口）
     */
    public static Optional<ToolCatalog> byName(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }
}
