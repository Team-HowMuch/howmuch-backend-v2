package com.amateur.howmuchbackend.application.commoncode.service;

import com.amateur.howmuchbackend.application.port.in.commoncode.CreateCommonCodeGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.GetCommonCodeGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.SearchCommonCodeGroupsUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.UpdateCommonCodeGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.CommonCodeDetailCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.CreateCommonCodeGroupCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.SearchCommonCodeGroupsCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.UpdateCommonCodeGroupCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeDetailResult;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupResult;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodeGroupPort;
import com.amateur.howmuchbackend.application.port.out.commoncode.SaveCommonCodeGroupPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.commoncode.status.CommonCodeStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CommonCodeAdminService implements
        SearchCommonCodeGroupsUseCase,
        GetCommonCodeGroupUseCase,
        CreateCommonCodeGroupUseCase,
        UpdateCommonCodeGroupUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final LoadCommonCodeGroupPort loadCommonCodeGroupPort;
    private final SaveCommonCodeGroupPort saveCommonCodeGroupPort;

    @Override
    @Transactional(readOnly = true)
    public List<CommonCodeGroupSummaryResult> search(SearchCommonCodeGroupsCommand command) {
        requireAdmin();
        return loadCommonCodeGroupPort.findAll(command.keyword()).stream()
                .map(this::toResult)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CommonCodeGroupResult get(String groupCode) {
        requireAdmin();
        return loadCommonCodeGroupPort.findByGroupCode(groupCode)
                .map(this::toResult)
                .orElseThrow(() -> ApplicationException.of(CommonCodeStatusCode.COMMON_CODE_GROUP_NOT_FOUND));
    }

    @Override
    @Transactional
    public CommonCodeGroupResult create(CreateCommonCodeGroupCommand command) {
        requireAdmin();
        if (loadCommonCodeGroupPort.findByGroupCode(command.groupCode()).isPresent()) {
            throw ApplicationException.of(CommonCodeStatusCode.COMMON_CODE_GROUP_ALREADY_EXISTS);
        }

        CommonCodeGroup group = CommonCodeGroup.create(
                command.groupCode(),
                command.groupName(),
                command.description(),
                command.attr1Name(),
                command.attr2Name(),
                command.attr3Name(),
                command.active()
        );
        return toResult(saveCommonCodeGroupPort.save(group));
    }

    @Override
    @Transactional
    public CommonCodeGroupResult update(String groupCode, UpdateCommonCodeGroupCommand command) {
        requireAdmin();
        CommonCodeGroup current = loadCommonCodeGroupPort.findByGroupCode(groupCode)
                .orElseThrow(() -> ApplicationException.of(CommonCodeStatusCode.COMMON_CODE_GROUP_NOT_FOUND));
        if (current.revision() != command.revision()) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }

        List<CommonCode> details = toDetails(groupCode, command.details());
        CommonCodeGroup updated = current.update(
                command.groupName(),
                command.description(),
                command.attr1Name(),
                command.attr2Name(),
                command.attr3Name(),
                command.active(),
                details
        );
        return toResult(saveCommonCodeGroupPort.save(updated));
    }

    private void requireAdmin() {
        Long userId = currentActorPort.getCurrentActor().userId();
        User user = loadUserPort.findById(userId)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        if (user.role() != UserRole.ADMIN) {
            throw ApplicationException.of(CommonStatusCode.FORBIDDEN);
        }
    }

    private List<CommonCode> toDetails(String groupCode, List<CommonCodeDetailCommand> commands) {
        Set<String> codes = new HashSet<>();
        return commands.stream()
                .map(command -> {
                    if (!codes.add(command.code())) {
                        throw ApplicationException.of(CommonCodeStatusCode.COMMON_CODE_DETAIL_ALREADY_EXISTS);
                    }
                    return new CommonCode(
                            groupCode,
                            command.code(),
                            command.name(),
                            command.description(),
                            command.sortOrder(),
                            command.attr1(),
                            command.attr2(),
                            command.attr3(),
                            command.active()
                    );
                })
                .toList();
    }

    private CommonCodeGroupSummaryResult toResult(
            com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroupSummary summary
    ) {
        return new CommonCodeGroupSummaryResult(
                summary.groupCode(),
                summary.groupName(),
                summary.description(),
                summary.active(),
                summary.revision(),
                summary.detailCount()
        );
    }

    private CommonCodeGroupResult toResult(CommonCodeGroup group) {
        return new CommonCodeGroupResult(
                group.groupCode(),
                group.groupName(),
                group.description(),
                group.attr1Name(),
                group.attr2Name(),
                group.attr3Name(),
                group.active(),
                group.revision(),
                group.details().stream()
                        .map(detail -> new CommonCodeDetailResult(
                                detail.code(),
                                detail.name(),
                                detail.description(),
                                detail.sortOrder(),
                                detail.attr1(),
                                detail.attr2(),
                                detail.attr3(),
                                detail.active()
                        ))
                        .toList()
        );
    }
}
