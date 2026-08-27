package com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response;

import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerChatResult;

import java.util.List;

public record TreasurerChatWebResponse(
        String greeting,
        List<Prompt> quickActions,
        List<Prompt> suggestions
) {
    public static TreasurerChatWebResponse from(TreasurerChatResult result) {
        return new TreasurerChatWebResponse(
                result.greeting(),
                result.quickActions().stream().map(Prompt::from).toList(),
                result.suggestions().stream().map(Prompt::from).toList()
        );
    }

    public record Prompt(
            String type,
            String label,
            String question
    ) {
        private static Prompt from(TreasurerChatResult.Prompt prompt) {
            return new Prompt(prompt.type().name(), prompt.label(), prompt.question());
        }
    }
}
