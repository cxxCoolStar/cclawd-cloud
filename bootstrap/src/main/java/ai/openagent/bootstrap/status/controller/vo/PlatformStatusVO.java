package ai.openagent.bootstrap.status.controller.vo;

import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.status.PlatformCapabilities;
import java.util.List;

/**
 * 平台状态视图对象
 *
 * @param configured       是否已完成初始配置
 * @param registrationOpen 是否开放注册
 * @param running          是否运行中
 * @param port             服务端口
 * @param mode             运行模式（local 等）
 * @param version          平台版本
 * @param uptime           运行时长（紧凑格式，如 "2h 3m 4s"）
 * @param agents           智能体状态摘要
 * @param channels         已接入渠道（未落地，恒为空）
 * @param provider         模型供应商摘要（apiKey 只透出是否已配置）
 * @param modelReady       模型是否就绪
 * @param cronJobs         定时任务数（未落地，恒 0）
 * @param plugins          插件数（未落地，恒 0）
 * @param capabilities     平台能力开关
 */
public record PlatformStatusVO(
        boolean configured,
        boolean registrationOpen,
        boolean running,
        int port,
        String mode,
        String version,
        String uptime,
        List<AgentStatusVO> agents,
        List<Object> channels,
        ProviderStatusVO provider,
        boolean modelReady,
        int cronJobs,
        int plugins,
        PlatformCapabilities capabilities) {

    /**
     * 智能体状态摘要
     *
     * @param id        智能体 ID
     * @param name      名称
     * @param model     使用的模型
     * @param workspace 工作区路径（未落地，恒为空）
     */
    public record AgentStatusVO(String id, String name, String model, String workspace) {

        /**
         * 由持久化记录装配
         */
        public static AgentStatusVO from(AgentRecord agent) {
            return new AgentStatusVO(agent.id(), agent.name(), agent.model(), "");
        }
    }

    /**
     * 模型供应商摘要
     *
     * @param name    供应商类型
     * @param model   模型名
     * @param apiBase API 地址
     * @param apiKey  密钥态（已配置时为 "configured"，否则为空，不透出明文）
     */
    public record ProviderStatusVO(String name, String model, String apiBase, String apiKey) {}
}
