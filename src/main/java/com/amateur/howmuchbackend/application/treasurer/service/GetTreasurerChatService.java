package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerChatUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerChatResult;
import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerCardCopy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetTreasurerChatService implements GetTreasurerChatUseCase {
    private final TreasurerCardCopy treasurerCardCopy;

    @Override
    public TreasurerChatResult getChat() {
        return new TreasurerChatResult(
                TreasurerCardCopy.GREETING,
                treasurerCardCopy.quickActions().stream().map(this::toPrompt).toList(),
                treasurerCardCopy.suggestions().stream().map(this::toPrompt).toList()
        );
    }

    private TreasurerChatResult.Prompt toPrompt(TreasurerCardCopy.Chip chip) {
        return new TreasurerChatResult.Prompt(chip.type(), chip.label(), chip.question());
    }
}
