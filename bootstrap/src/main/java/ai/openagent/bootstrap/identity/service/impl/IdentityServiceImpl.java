package ai.openagent.bootstrap.identity.service.impl;

import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.service.IdentityService;
import ai.openagent.bootstrap.persistence.OpenAgentStore;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 身份服务实现
 * <p>
 * 从持久层读取种子用户作为唯一事实来源（避免与 seedDefaults 双份维护）
 * </p>
 */
@Service
@RequiredArgsConstructor
public class IdentityServiceImpl implements IdentityService {

    private final OpenAgentStore store;

    @Override
    public CurrentUserVO currentUser() {
        return store.findUser(OpenAgentStore.LOCAL_USER_ID)
                .map(CurrentUserVO::local)
                .orElseThrow(() -> new ServiceException(
                        "local user is not seeded", BaseErrorCode.SERVICE_UNAVAILABLE_ERROR));
    }
}
