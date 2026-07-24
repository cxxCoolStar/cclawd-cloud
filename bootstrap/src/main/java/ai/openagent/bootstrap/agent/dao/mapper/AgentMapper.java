package ai.openagent.bootstrap.agent.dao.mapper;

import ai.openagent.bootstrap.agent.dao.entity.AgentDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMapper extends BaseMapper<AgentDO> {

    @Delete("""
            DELETE FROM channel_inbound_messages
            WHERE binding_id IN (SELECT id FROM channel_bindings WHERE agent_id = #{agentId})
            """)
    int deleteChannelInboundMessages(String agentId);

    @Delete("""
            DELETE FROM channel_conversations
            WHERE binding_id IN (SELECT id FROM channel_bindings WHERE agent_id = #{agentId})
            """)
    int deleteChannelConversations(String agentId);

    @Delete("DELETE FROM channel_bindings WHERE agent_id = #{agentId}")
    int deleteChannelBindings(String agentId);

    @Delete("DELETE FROM session_events WHERE agent_id = #{agentId}")
    int deleteSessionEvents(String agentId);

    @Delete("DELETE FROM session_messages WHERE agent_id = #{agentId}")
    int deleteSessionMessages(String agentId);

    @Delete("DELETE FROM sessions WHERE agent_id = #{agentId}")
    int deleteSessions(String agentId);

    @Delete("""
            DELETE FROM tool_executions
            WHERE run_id IN (SELECT id FROM agent_runs WHERE agent_id = #{agentId})
            """)
    int deleteToolExecutions(String agentId);

    @Delete("DELETE FROM agent_runs WHERE agent_id = #{agentId}")
    int deleteAgentRuns(String agentId);

    @Delete("DELETE FROM agent_tools WHERE agent_id = #{agentId}")
    int deleteAgentTools(String agentId);

    @Delete("DELETE FROM agent_mcp_servers WHERE agent_id = #{agentId}")
    int deleteAgentMcpServers(String agentId);
}
