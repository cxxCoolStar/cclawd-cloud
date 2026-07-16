package ai.openagent.bootstrap.status.service;

import ai.openagent.bootstrap.status.controller.vo.PlatformStatusVO;

/**
 * 平台状态服务接口
 */
public interface PlatformStatusService {

    /**
     * 查询平台当前状态
     */
    PlatformStatusVO currentStatus();
}
