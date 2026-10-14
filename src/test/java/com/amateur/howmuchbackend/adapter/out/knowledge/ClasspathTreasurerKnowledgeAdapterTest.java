package com.amateur.howmuchbackend.adapter.out.knowledge;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerKnowledge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("배포된 AI 비서 서비스 안내 문서")
class ClasspathTreasurerKnowledgeAdapterTest {
    @Test
    @DisplayName("배포 문서를 읽고 안내 범위와 미제공 기능 섹션을 갖는다")
    void load_bundledDocument() {
        TreasurerKnowledge knowledge = new ClasspathTreasurerKnowledgeAdapter().load();

        assertThat(knowledge.sourceIds())
                .contains("ABOUT", "AI", "CREATE", "JOIN", "PAY", "REMIND", "GROUP", TreasurerKnowledge.NOT_YET_SOURCE);
        assertThat(knowledge.text()).contains("30분 동안 다시 보낼 수 없다");
    }

    @Test
    @DisplayName("배포 문서는 내부 업체 이름과 API 경로를 담지 않는다")
    void load_bundledDocument_hasNoInternalDetails() {
        String text = new ClasspathTreasurerKnowledgeAdapter().load().text();

        assertThat(text).doesNotContainIgnoringCase("codef").doesNotContain("/api/");
    }
}
