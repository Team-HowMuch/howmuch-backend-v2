package com.amateur.howmuchbackend.application.port.in.user;

public interface ManageMyPushTargetUseCase {
    void register(String firebaseInstallationId);

    void clear();
}
