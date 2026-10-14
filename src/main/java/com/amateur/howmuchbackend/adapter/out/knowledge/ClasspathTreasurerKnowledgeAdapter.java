package com.amateur.howmuchbackend.adapter.out.knowledge;

import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerKnowledgePort;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerKnowledge;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 배포에 포함된 서비스 안내 문서를 시작할 때 한 번 읽는다.
 *
 * <p>문서가 없거나 근거 ID가 깨져 있으면 서버가 뜨지 않는다. 잘못된 문서로 사용자에게 안내하는 것보다
 * 배포 단계에서 막는 편이 낫다.
 */
@Component
public class ClasspathTreasurerKnowledgeAdapter implements LoadTreasurerKnowledgePort {
    static final String LOCATION = "treasurer/service-knowledge.md";

    private final TreasurerKnowledge knowledge;

    public ClasspathTreasurerKnowledgeAdapter() {
        this.knowledge = TreasurerKnowledge.parse(read(LOCATION));
    }

    @Override
    public TreasurerKnowledge load() {
        return knowledge;
    }

    private static String read(String location) {
        try (InputStream input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("서비스 안내 문서를 읽을 수 없습니다: " + location, exception);
        }
    }
}
