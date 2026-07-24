package ai.openagent.bootstrap.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("agents")
public class AgentDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("provider_id")
    private String providerId;

    @TableField("model")
    private String model;

    @TableField("system_prompt")
    private String systemPrompt;

    @TableField("created_at")
    private Long createdAt;

    @TableField("updated_at")
    private Long updatedAt;
}
