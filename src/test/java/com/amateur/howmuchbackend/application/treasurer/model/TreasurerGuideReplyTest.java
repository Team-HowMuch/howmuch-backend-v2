package com.amateur.howmuchbackend.application.treasurer.model;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 비서 서비스 안내 LLM 응답")
class TreasurerGuideReplyTest {

    @Test
    @DisplayName("데이터 의도로 사용법(GUIDE)을 주면 의도를 비워 키워드 분류로 넘긴다")
    void guideIntent_cleared() {
        var reply = new TreasurerGuideReply(
                TreasurerGuideReply.Route.DATA, null, null, null, InquiryIntent.GUIDE, null, null);

        assertThat(reply.intent()).isNull();
        assertThat(reply.sources()).isEmpty();
        assertThat(reply.followUps()).isEmpty();
    }

    @Test
    @DisplayName("데이터 의도와 미분류는 그대로 둔다")
    void dataIntents_kept() {
        assertThat(new TreasurerGuideReply(TreasurerGuideReply.Route.DATA, null, List.of(), List.of(),
                InquiryIntent.SEND_SETTLEMENTS, null, null).intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(new TreasurerGuideReply(TreasurerGuideReply.Route.DATA, null, List.of(), List.of(),
                InquiryIntent.UNKNOWN, null, null).intent()).isEqualTo(InquiryIntent.UNKNOWN);
    }
}
