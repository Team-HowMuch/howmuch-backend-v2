package com.amateur.howmuchbackend.application.port.in.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;

public interface AskTreasurerUseCase {
    TreasurerAnswerResult ask(AskTreasurerCommand command);
}
