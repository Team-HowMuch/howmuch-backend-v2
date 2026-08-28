package com.amateur.howmuchbackend.application.port.out.treasurer;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;

import java.util.Optional;

public interface GenerateTreasurerAnswerPort {
    Optional<String> generate(TreasurerAnswerDraft draft);
}
