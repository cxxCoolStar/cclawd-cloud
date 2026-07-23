package ai.openagent.bootstrap.channel.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Channel account binding used by the read-only operations view. */
@Data
@TableName("channel_bindings")
public class ChannelBindingDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String userId;
    private String agentId;
    private String channelType;
    private String accountId;
    private String displayName;
    private Boolean enabled;
    private Boolean sharedIdentity;
    private Long createdAt;
    private Long updatedAt;
}
