package com.amateur.howmuchbackend.application.port.in.user.command;

public record SearchAdminUserCommand(String phoneNumber) {
    @Override
    public String toString() {
        return "SearchAdminUserCommand[phoneNumber=***]";
    }
}
