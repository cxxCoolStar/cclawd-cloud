package ai.openagent.bootstrap.identity.service.impl;

import ai.openagent.bootstrap.identity.controller.request.AdminCreateUserRequest;
import ai.openagent.bootstrap.identity.controller.request.AdminResetPasswordRequest;
import ai.openagent.bootstrap.identity.controller.request.AdminUpdateUserRequest;
import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.controller.vo.UserListVO;
import ai.openagent.bootstrap.identity.controller.vo.UserMutationVO;
import ai.openagent.bootstrap.identity.service.UserAdminService;
import ai.openagent.bootstrap.identity.service.UserManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserManagementServiceImpl implements UserManagementService {

    private final UserAdminService userAdminService;

    @Override
    public UserListVO list() {
        return new UserListVO(userAdminService.listUsers());
    }

    @Override
    public UserMutationVO create(AdminCreateUserRequest request) {
        CurrentUserVO.UserVO user = userAdminService.createUser(
                request.username(),
                request.email(),
                request.password(),
                request.displayName(),
                request.role(),
                request.agentQuota());
        return new UserMutationVO(user);
    }

    @Override
    public UserMutationVO update(String id, AdminUpdateUserRequest request) {
        CurrentUserVO.UserVO user = userAdminService.updateUser(
                id, request.displayName(), request.role(), request.status(), request.agentQuota());
        return new UserMutationVO(user);
    }

    @Override
    public void delete(String id) {
        userAdminService.deleteUser(id);
    }

    @Override
    public void resetPassword(String id, AdminResetPasswordRequest request) {
        userAdminService.resetPassword(id, request.password());
    }
}