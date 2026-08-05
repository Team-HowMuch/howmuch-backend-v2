package com.amateur.howmuchbackend.adapter.out.sms;

import com.amateur.howmuchbackend.application.port.out.auth.SmsSenderPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.infra.solapi.config.SolapiSmsSenderProperties;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "howmuch.auth.sms.sender", havingValue = "solapi")
public class SolapiSmsSenderAdapter implements SmsSenderPort {
    private static final String MESSAGE_FORMAT = "[HowMuch] 인증번호는 %s입니다.";

    private final DefaultMessageService messageService;
    private final SolapiSmsSenderProperties properties;

    @Override
    public void send(String phoneNumber, String verificationCode) {
        Message message = new Message();
        message.setFrom(properties.sender());
        message.setTo(phoneNumber);
        message.setText(MESSAGE_FORMAT.formatted(verificationCode));

        try {
            messageService.send(message, null);
        } catch (Exception exception) {
            throw new ApplicationException(AuthStatusCode.SMS_SEND_FAILED, exception);
        }
    }
}
