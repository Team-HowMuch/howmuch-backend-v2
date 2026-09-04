package com.amateur.howmuchbackend.application.port.in.notification.command;

public record GetMyNotificationsCommand(String cursor, Integer size) {}
