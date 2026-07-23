package ai.openagent.bootstrap.identity.service;

import ai.openagent.bootstrap.identity.controller.request.AdminCreateUserRequest;
import ai.openagent.bootstrap.identity.controller.request.AdminResetPasswordRequest;
import ai.openagent.bootstrap.identity.controller.request.AdminUpdateUserRequest;
import ai.openagent.bootstrap.identity.controller.vo.UserListVO;
import ai.openagent.bootstrap.identity.controller.vo.UserMutationVO;

public interface UserManagementService {

    UserListVO list();

    UserMutationVO create(AdminCreateUserRequest request);

    UserMutationVO update(String id, AdminUpdateUserRequest request);

    void delete(String id);

    void resetPassword(String id, AdminResetPasswordRequest request);
}