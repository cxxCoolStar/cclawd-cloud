package ai.openagent.bootstrap.identity.dao.mapper;

import ai.openagent.bootstrap.identity.dao.entity.ApiKeyDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKeyDO> {}