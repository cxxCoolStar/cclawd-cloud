package ai.openagent.bootstrap.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Workspace 配置属性
 *
 * @param historyEnabled 会话 workspace 版本历史开关：开启后每轮运行结束
 *                       自动把会话 workspace 快照提交到 workspace 外的
 *                       bare git 仓库（{workspaceRoot}/.history/），
 *                       供用户回滚 agent 的文件修改
 */
@ConfigurationProperties(prefix = "openagent.workspace")
public record WorkspaceProperties(@DefaultValue("true") boolean historyEnabled) {}
