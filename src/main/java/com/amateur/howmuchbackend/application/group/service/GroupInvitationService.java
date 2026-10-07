package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.notification.service.BusinessNotificationService;
import com.amateur.howmuchbackend.application.port.in.group.*;
import com.amateur.howmuchbackend.application.port.in.group.command.CreateGroupInvitationCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupInvitationResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupInvitationStorePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.*;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupInvitationService implements CreateGroupInvitationUseCase, GetGroupInvitationUseCase,
        AcceptGroupInvitationUseCase, DeclineGroupInvitationUseCase {
    private final CurrentActorPort actor;
    private final GroupAccessService access;
    private final LoadUserPort users;
    private final LoadGroupMemberPort members;
    private final SaveGroupMemberPort saveMembers;
    private final GroupInvitationStorePort invitations;
    private final PublicIdPolicy publicIds;
    private final BusinessNotificationService notifications;
    private final Clock clock;

    @Override
    @Transactional
    public GroupInvitationResult create(Long groupId, CreateGroupInvitationCommand command) {
        Long inviterId = actor.getCurrentActor().userId();
        Group group = access.loadActiveForUpdate(groupId);
        access.requireOwner(groupId, inviterId);
        User recipient = users.findByPublicId(publicIds.normalize(command.publicId()))
                .filter(User::active).orElseThrow(() -> ApplicationException.of(
                        GroupStatusCode.GROUP_MEMBER_CANDIDATE_NOT_FOUND));
        if (inviterId.equals(recipient.id()))
            throw ApplicationException.of(GroupStatusCode.INVALID_GROUP_INVITATION);
        if (members.findByGroupIdAndUserId(groupId, recipient.id()).filter(GroupMember::isActive).isPresent())
            throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_ALREADY_ACTIVE);
        LocalDateTime now = now();
        var pending = invitations.findPending(groupId, recipient.id());
        if (pending.isPresent()) {
            GroupInvitation previous = pending.get();
            if (previous.isPendingAt(now) && previous.inviterUserId().equals(inviterId)) return result(previous, group, now);
            invitations.update(previous.isPendingAt(now) ? previous.cancel(now) : previous.expire(now));
        }
        GroupInvitation invitation = invitations.save(GroupInvitation.pending(groupId, inviterId, recipient.id(), now));
        notifications.groupInvitation(invitation, group.name(), inviterName(inviterId));
        return result(invitation, group, now);
    }

    @Override
    @Transactional(readOnly = true)
    public GroupInvitationResult get(UUID invitationId) {
        GroupInvitation invitation = requireRecipient(invitationId, false);
        return result(invitation, access.loadActive(invitation.groupId()), now());
    }

    @Override
    @Transactional
    public GroupInvitationResult accept(UUID invitationId) {
        Long recipientId = actor.getCurrentActor().userId();
        users.findByIdForUpdate(recipientId).filter(User::active).orElseThrow(() -> ApplicationException.of(
                GroupStatusCode.GROUP_INVITATION_NOT_FOUND));
        GroupInvitation initial = requireRecipient(invitationId, false);
        Group group = access.loadActiveForUpdate(initial.groupId());
        GroupInvitation invitation = requireRecipient(invitationId, true);
        LocalDateTime now = now();
        if (invitation.status() == GroupInvitationStatus.ACCEPTED) return result(invitation, group, now);
        requirePending(invitation, now);
        if (!group.ownerId().equals(invitation.inviterUserId()))
            throw ApplicationException.of(GroupStatusCode.GROUP_INVITATION_NOT_PENDING);
        members.findByGroupIdAndUserIdForUpdate(group.id(), recipientId).ifPresentOrElse(member -> {
            if (!member.isActive() && !invitation.createdAt().isAfter(member.leftAt()))
                throw ApplicationException.of(GroupStatusCode.GROUP_INVITATION_NOT_PENDING);
            if (member.status() == GroupMemberStatus.LEFT) saveMembers.update(member.rejoin(now));
            else if (member.status() == GroupMemberStatus.REMOVED) saveMembers.update(member.restore(now));
        }, () -> saveMembers.save(GroupMember.member(group.id(), recipientId, now)));
        return result(invitations.update(invitation.accept(now)), group, now);
    }

    @Override
    @Transactional
    public GroupInvitationResult decline(UUID invitationId) {
        GroupInvitation initial = requireRecipient(invitationId, false);
        Group group = access.loadActiveForUpdate(initial.groupId());
        GroupInvitation invitation = requireRecipient(invitationId, true);
        LocalDateTime now = now();
        if (invitation.status() == GroupInvitationStatus.DECLINED) return result(invitation, group, now);
        requirePending(invitation, now);
        return result(invitations.update(invitation.decline(now)), group, now);
    }

    private GroupInvitation requireRecipient(UUID id, boolean lock) {
        Long userId = actor.getCurrentActor().userId();
        return (lock ? invitations.findByIdForUpdate(id) : invitations.findById(id))
                .filter(invitation -> invitation.recipientUserId().equals(userId))
                .orElseThrow(() -> ApplicationException.of(GroupStatusCode.GROUP_INVITATION_NOT_FOUND));
    }
    private void requirePending(GroupInvitation invitation, LocalDateTime now) {
        if (invitation.effectiveStatus(now) == GroupInvitationStatus.EXPIRED)
            throw ApplicationException.of(GroupStatusCode.GROUP_INVITATION_EXPIRED);
        if (!invitation.isPendingAt(now))
            throw ApplicationException.of(GroupStatusCode.GROUP_INVITATION_NOT_PENDING);
    }
    private GroupInvitationResult result(GroupInvitation invitation, Group group, LocalDateTime now) {
        return new GroupInvitationResult(invitation.id(), group.id(), group.name(),
                inviterName(invitation.inviterUserId()),
                invitation.status() == GroupInvitationStatus.PENDING && !group.ownerId().equals(invitation.inviterUserId())
                        ? GroupInvitationStatus.CANCELLED : invitation.effectiveStatus(now), invitation.expiresAt());
    }
    private String inviterName(Long userId) { return users.findById(userId).map(User::name).orElse("회원"); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
