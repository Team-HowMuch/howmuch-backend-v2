package com.amateur.howmuchbackend.domain.treasurer;

/**
 * 답변 문장을 누가 만들었는지. 사용자는 둘을 구분할 수 없으므로 둘 다 저장하고 둘 다 신고할 수 있다.
 */
public enum TreasurerAnswerGeneratedBy {
    LLM,
    SERVER
}
