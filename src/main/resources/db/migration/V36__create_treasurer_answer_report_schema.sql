-- 두 테이블 모두 created_at만 두고 updated_at을 두지 않는다.
-- treasurer_answers는 한 번 쓰고 고치지 않으며, treasurer_answer_reports의 변경은
-- resolved_at이 이미 기록한다. 따라서 후속 작업에서 엔티티를 만들 때
-- BaseTimeJpaEntity를 상속하면 안 된다. 상속하면 updated_at 매핑 때문에
-- ddl-auto: validate가 기동 시점에 실패한다. created_at은 엔티티가 직접 채운다.
-- 답변 시각은 영속화 시각이 아니라 사용자에게 답변이 나간 시각이어야 한다.

CREATE TABLE treasurer_answers (
    id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    question VARCHAR(500) NOT NULL,
    answer VARCHAR(2000) NOT NULL,
    intent VARCHAR(30) NOT NULL,
    generated_by VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_treasurer_answers PRIMARY KEY (id),
    CONSTRAINT fk_treasurer_answers_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- 유일성은 PK가 이미 보장한다. 이 제약은 신고가 답변과 소유자를 함께 참조할 수 있도록
    -- 복합 FK의 대상을 만들어 주기 위해 존재하며, 소유 확인 조회도 이 인덱스를 탄다.
    CONSTRAINT uk_treasurer_answers_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_treasurer_answers_generated_by
        CHECK (generated_by IN ('LLM', 'SERVER')),
    CONSTRAINT ck_treasurer_answers_question_not_blank
        CHECK (CHAR_LENGTH(BTRIM(question)) > 0),
    CONSTRAINT ck_treasurer_answers_answer_not_blank
        CHECK (CHAR_LENGTH(BTRIM(answer)) > 0)
);

-- 보관 기간이 지난 답변을 훑는 정리 작업을 위한 인덱스.
CREATE INDEX idx_treasurer_answers_created_at
    ON treasurer_answers (created_at);

COMMENT ON TABLE treasurer_answers IS
    '사용자에게 나간 AI 비서 답변. 신고 대상을 가리키기 위해 모든 답변을 남긴다.';
COMMENT ON COLUMN treasurer_answers.question IS
    '질문 원문. 요청 DTO가 500자를 넘는 질문을 400으로 막으므로 길이가 정확히 일치한다. 모임 이름이나 금액 같은 개인적인 내용이 들어갈 수 있어 보관 기간이 지나면 지운다.';
COMMENT ON COLUMN treasurer_answers.answer IS
    '사용자가 본 답변 문장. LLM 응답은 파서가 500자로 자르고 서버 문구는 그보다 짧지만, 길이를 넘기면 저장이 실패해 그 답변만 신고할 수 없게 되므로 넉넉히 잡는다. 선언 길이는 저장 공간에 영향을 주지 않는다.';
COMMENT ON COLUMN treasurer_answers.intent IS
    'InquiryIntent 이름. 신고를 진단할 때 보는 참고 값이라 의도가 늘어도 마이그레이션이 필요 없도록 CHECK를 두지 않는다.';
COMMENT ON COLUMN treasurer_answers.generated_by IS
    '문장을 LLM이 만들었는지(LLM) 서버 문구인지(SERVER). 사용자는 구분할 수 없으므로 둘 다 저장하고 둘 다 신고할 수 있다.';

CREATE TABLE treasurer_answer_reports (
    id UUID NOT NULL,
    answer_id UUID NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    reason VARCHAR(20) NOT NULL,
    detail VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    resolved_at TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT pk_treasurer_answer_reports PRIMARY KEY (id),
    -- 답변과 소유자를 함께 참조한다. 신고자가 그 답변을 받은 사용자가 아니면 행 자체가 들어가지 않는다.
    -- reporter_user_id에 users를 향한 FK를 따로 두지 않는 이유도 이것이다.
    -- treasurer_answers.user_id가 이미 users를 참조하므로 실재하는 사용자임이 전이적으로 보장된다.
    CONSTRAINT fk_treasurer_answer_reports_answer
        FOREIGN KEY (answer_id, reporter_user_id)
            REFERENCES treasurer_answers (id, user_id)
            ON DELETE RESTRICT,
    CONSTRAINT uk_treasurer_answer_reports_answer UNIQUE (answer_id),
    CONSTRAINT ck_treasurer_answer_reports_reason
        CHECK (reason IN ('INACCURATE', 'OFFENSIVE', 'PRIVACY', 'OTHER')),
    CONSTRAINT ck_treasurer_answer_reports_status
        CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_treasurer_answer_reports_detail_not_blank
        CHECK (detail IS NULL OR CHAR_LENGTH(BTRIM(detail)) > 0),
    -- 처리 시각은 처리 상태와 함께 채워지고 함께 비워진다.
    -- RESOLVED에서 OPEN으로 되돌리는 것은 막지 못한다. 단방향 전이는 도메인이 지킨다.
    CONSTRAINT ck_treasurer_answer_reports_resolved_at
        CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

COMMENT ON TABLE treasurer_answer_reports IS
    '답변 한 건에 대한 신고. 한 답변에 하나만 달리고, 신고자는 그 답변을 받은 사용자뿐이다. 관리자 API가 없어 조회는 DB로 직접 한다.';
COMMENT ON CONSTRAINT fk_treasurer_answer_reports_answer ON treasurer_answer_reports IS
    'RESTRICT라서 신고가 달린 답변은 보관 정리가 지우지 못한다. 신고된 답변의 질문 원문은 보관 기간과 무관하게 남으며, 처리(RESOLVED)한 신고를 지우는 시점이 실제 삭제 시점이다.';
COMMENT ON COLUMN treasurer_answer_reports.detail IS
    '사용자가 직접 쓴 신고 사유. 질문 원문과 같은 수준으로 개인적인 내용이 들어갈 수 있다.';
