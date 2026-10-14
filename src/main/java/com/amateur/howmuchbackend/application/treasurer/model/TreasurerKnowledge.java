package com.amateur.howmuchbackend.application.treasurer.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 비서가 사용법 질문에 답할 때 근거로 삼는 서비스 안내 문서.
 *
 * <p>문서의 {@code ## [ID]} 제목과 {@code - [ID]} 항목이 근거 ID다. LLM은 답변과 함께 근거 ID를 돌려주고,
 * 서버는 그 ID가 실제로 이 문서에 있는지 확인한다. 없는 ID를 대면 지어낸 답으로 보고 쓰지 않는다.
 */
public record TreasurerKnowledge(String text, Set<String> sourceIds) {
    /** 아직 제공하지 않는 기능을 모은 섹션. 이 섹션만 근거로 든 답은 "없다"는 안내여야 한다. */
    public static final String NOT_YET_SOURCE = "NOT-YET";

    private static final Pattern SOURCE_ID = Pattern.compile("(?m)^(?:##|-)\\s*\\[([A-Z][A-Z0-9-]*)]");

    public TreasurerKnowledge {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("서비스 안내 문서가 비어 있습니다.");
        }
        if (sourceIds == null || sourceIds.isEmpty()) {
            throw new IllegalArgumentException("서비스 안내 문서에 근거 ID가 없습니다.");
        }
        sourceIds = Set.copyOf(sourceIds);
    }

    /**
     * 문서를 읽어 근거 ID를 모은다. 같은 ID가 두 번 나오면 어느 쪽을 근거로 든 것인지 알 수 없어 거부한다.
     */
    public static TreasurerKnowledge parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("서비스 안내 문서가 비어 있습니다.");
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = SOURCE_ID.matcher(markdown);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("서비스 안내 문서의 근거 ID가 중복됩니다: " + id);
            }
        }
        return new TreasurerKnowledge(markdown.strip(), ids);
    }

    public boolean knows(String sourceId) {
        return sourceId != null && sourceIds.contains(sourceId);
    }
}
