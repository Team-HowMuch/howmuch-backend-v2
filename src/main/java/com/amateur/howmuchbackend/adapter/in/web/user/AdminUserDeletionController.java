package com.amateur.howmuchbackend.adapter.in.web.user;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.user.docs.AdminUserDeletionDocs;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.SearchAdminUserWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.response.AdminUserSearchWebResponse;
import com.amateur.howmuchbackend.application.port.in.user.DeleteUserAsAdminUseCase;
import com.amateur.howmuchbackend.application.port.in.user.SearchAdminUserUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/admin/users")
public class AdminUserDeletionController implements AdminUserDeletionDocs {
    private final SearchAdminUserUseCase searchAdminUserUseCase;
    private final DeleteUserAsAdminUseCase deleteUserAsAdminUseCase;

    @Override
    @PostMapping("/search")
    public ApiResponse<AdminUserSearchWebResponse> searchUser(
            @Valid @RequestBody SearchAdminUserWebRequest request
    ) {
        return ApiResponse.success(AdminUserSearchWebResponse.from(searchAdminUserUseCase.search(request.toCommand())));
    }

    @Override
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId) {
        deleteUserAsAdminUseCase.delete(userId);
        return ApiResponse.success();
    }
}
