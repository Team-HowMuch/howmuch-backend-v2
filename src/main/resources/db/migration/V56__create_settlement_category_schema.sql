-- 정산 카테고리(STL-008). 기존 테이블은 바꾸지 않고 추가만 한다.

-- 카테고리 목록은 은행(BANK)처럼 공통 코드로 관리한다.
-- name: 표시명, description: 세부 예시, attr1: 아이콘 키(앱이 아이콘 이미지에 연결), attr2: 정산 사용 여부(Y/N)
INSERT INTO common_code_group (
    group_code,
    group_name,
    description,
    attr1_name,
    attr2_name,
    use_yn
)
VALUES (
    'SETTLEMENT_CATEGORY',
    '정산 카테고리',
    '정산 지출 카테고리. attr2=Y만 정산 자동 분류와 수정 선택지에 쓴다.',
    'icon',
    'settlement_use',
    TRUE
)
ON CONFLICT (group_code) DO UPDATE
SET group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    attr1_name = EXCLUDED.attr1_name,
    attr2_name = EXCLUDED.attr2_name,
    use_yn = EXCLUDED.use_yn,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO common_code_detail (
    group_code,
    code,
    name,
    description,
    sort_order,
    attr1,
    attr2,
    use_yn
)
VALUES
    ('SETTLEMENT_CATEGORY', 'FOOD', '식비', '음식점, 편의점 식품, 구내식당', 1, 'food', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'CAFE_SNACK', '카페 / 간식', '카페, 베이커리, 디저트', 2, 'cafe-snack', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'CONVENIENCE_MART', '편의점 / 마트 / 잡화', '편의점 생활용품, 슈퍼마켓, 대형마트', 3, 'convenience-mart', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'ALCOHOL_ENTERTAINMENT', '술 / 유흥', '주류, 노래방, 클럽', 4, 'alcohol-entertainment', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'SHOPPING', '쇼핑', '의류, 신발, 전자기기, 온라인쇼핑', 5, 'shopping', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'HOBBY_LEISURE', '취미 / 여가', '영화, 게임, 스포츠, 도서', 6, 'hobby-leisure', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'SUBSCRIPTION', '구독', 'OTT, 음악 스트리밍, 클라우드·소프트웨어 구독', 7, 'subscription', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'MEDICAL_HEALTH', '의료 / 건강 / 피트니스', '병원, 약국, 헬스장', 8, 'medical-health', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'HOUSING_TELECOM', '주거 / 통신', '월세, 관리비, 통신비, 인터넷', 9, 'housing-telecom', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'INSURANCE_TAX_FINANCE', '보험 / 세금 / 기타금융', '보험료, 세금 납부, 금융 수수료', 10, 'insurance-tax-finance', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'BEAUTY', '미용', '미용실, 네일, 화장품', 11, 'beauty', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'TRANSPORT_CAR', '교통 / 자동차', '대중교통, 택시, 주유, 주차', 12, 'transport-car', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'FLIGHT', '항공', '항공권, 공항 이용', 13, 'flight', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'LODGING', '숙박', '호텔, 펜션, 에어비앤비', 14, 'lodging', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'EDUCATION', '교육', '학원, 온라인강의, 교재', 15, 'education', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'LIVING', '생활', '청소, 세탁, 수리 서비스', 16, 'living', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'DONATION', '기부 / 후원', '정기 기부, 크라우드펀딩', 17, 'donation', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'UNCATEGORIZED', '미분류', '자동 분류 실패 또는 미지정', 18, 'uncategorized', 'Y', TRUE),
    ('SETTLEMENT_CATEGORY', 'TRANSFER', '이체', '계좌 거래 분류로 확장할 때 사용', 19, 'transfer', 'N', TRUE),
    ('SETTLEMENT_CATEGORY', 'INCOME', '급여(수입)', '계좌 거래 분류로 확장할 때 사용', 20, 'income', 'N', TRUE),
    ('SETTLEMENT_CATEGORY', 'CARD_PAYMENT', '카드 대금', '계좌 거래 분류로 확장할 때 사용', 21, 'card-payment', 'N', TRUE),
    ('SETTLEMENT_CATEGORY', 'SAVING_INVESTMENT', '저축 / 투자', '계좌 거래 분류로 확장할 때 사용', 22, 'saving-investment', 'N', TRUE),
    ('SETTLEMENT_CATEGORY', 'DEFERRED_PAYMENT', '후불 결제 대금', '계좌 거래 분류로 확장할 때 사용', 23, 'deferred-payment', 'N', TRUE)
ON CONFLICT (group_code, code) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    attr1 = EXCLUDED.attr1,
    attr2 = EXCLUDED.attr2,
    use_yn = EXCLUDED.use_yn,
    updated_at = CURRENT_TIMESTAMP;

-- 정산마다 하나. 자동 분류 결과와 분류 작업 상태를 함께 둔다.
-- 상태·선점 규칙은 settlement_deposit_reconciliation_jobs(V52)를 따른다.
-- 선점은 attempt_count < max_attempts인 PENDING·RETRY 행만 가져가며, 가져갈 때마다 attempt_count를 1 올린다.
-- 선점이 만료된 PROCESSING 행은 바로 다시 가져가지 않는다. 시도가 남았으면 RETRY(PROCESSING_TIMEOUT)로,
-- 다 썼으면 PROCESSING_TIMEOUT 미분류로 끝낸다. 회수도 다음 선점에서 시도 횟수에 들어간다.
-- 성공으로 끝낼 때는 last_failure_code를 비운다.
-- source: RULE(규칙 사전), AI, UNCATEGORIZED(근거 없음·실패), RECURRING(정기 정산 설정)
CREATE TABLE settlement_categories (
    settlement_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    category_code VARCHAR(50),
    source VARCHAR(20),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lease_token UUID,
    lease_until TIMESTAMP(6) WITHOUT TIME ZONE,
    classified_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(40),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_categories PRIMARY KEY (settlement_id),
    CONSTRAINT fk_settlement_categories_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    CONSTRAINT ck_settlement_categories_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY', 'CLASSIFIED')
    ),
    CONSTRAINT ck_settlement_categories_code CHECK (
        category_code IS NULL OR category_code ~ '^[A-Z][A-Z_]{0,49}$'
    ),
    CONSTRAINT ck_settlement_categories_source CHECK (
        source IS NULL OR source IN ('RULE', 'AI', 'UNCATEGORIZED', 'RECURRING')
    ),
    CONSTRAINT ck_settlement_categories_attempts CHECK (
        max_attempts BETWEEN 1 AND 3
        AND attempt_count BETWEEN 0 AND max_attempts
        AND (status <> 'PENDING' OR attempt_count = 0)
        AND (status <> 'PROCESSING' OR attempt_count > 0)
        AND (status <> 'RETRY' OR (attempt_count > 0 AND attempt_count < max_attempts))
    ),
    -- PostgreSQL CHECK는 UNKNOWN도 통과시키므로 IS NULL/IS NOT NULL을 모두 명시한다.
    CONSTRAINT ck_settlement_categories_lifecycle CHECK (
        (status IN ('PENDING', 'RETRY')
            AND next_attempt_at IS NOT NULL
            AND lease_token IS NULL AND lease_until IS NULL
            AND classified_at IS NULL AND category_code IS NULL AND source IS NULL)
        OR (status = 'PROCESSING'
            AND next_attempt_at IS NULL
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND classified_at IS NULL AND category_code IS NULL AND source IS NULL)
        OR (status = 'CLASSIFIED'
            AND next_attempt_at IS NULL
            AND lease_token IS NULL AND lease_until IS NULL
            AND classified_at IS NOT NULL AND category_code IS NOT NULL AND source IS NOT NULL)
    ),
    CONSTRAINT ck_settlement_categories_uncategorized CHECK (
        source IS NULL
        OR (source = 'UNCATEGORIZED' AND category_code = 'UNCATEGORIZED')
        OR (source <> 'UNCATEGORIZED' AND category_code <> 'UNCATEGORIZED')
    ),
    CONSTRAINT ck_settlement_categories_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'PROVIDER_ERROR', 'INVALID_RESPONSE', 'PROCESSING_TIMEOUT'
        )
    ),
    -- 실패 사유는 재시도 중이거나, 재시도를 다 쓰고 미분류로 끝났을 때만 남는다.
    CONSTRAINT ck_settlement_categories_failure_state CHECK (
        (status = 'PENDING' AND last_failure_code IS NULL)
        OR (status = 'RETRY' AND last_failure_code IS NOT NULL)
        OR status = 'PROCESSING'
        OR (status = 'CLASSIFIED' AND (last_failure_code IS NULL OR source = 'UNCATEGORIZED'))
    ),
    -- 실패 사유를 남긴 채 끝나는 행은 시도를 모두 쓴 행뿐이다. 한 번 실패하고 바로 끝내지 않는다.
    -- 요청 시점에 바로 미분류로 끝난 행(attempt 0)에도 실패 사유가 없다.
    CONSTRAINT ck_settlement_categories_failure_exhausted CHECK (
        status <> 'CLASSIFIED' OR last_failure_code IS NULL OR attempt_count = max_attempts
    ),
    CONSTRAINT ck_settlement_categories_times CHECK (
        created_at <= updated_at
        AND (classified_at IS NULL OR (classified_at >= created_at AND classified_at <= updated_at))
    )
);

CREATE INDEX idx_settlement_categories_due
    ON settlement_categories (next_attempt_at, settlement_id)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_settlement_categories_expired_lease
    ON settlement_categories (lease_until, settlement_id)
    WHERE status = 'PROCESSING';

-- 회원별 "내 카테고리" 수정값. 행이 없으면 settlement_categories의 자동 분류 값을 쓴다.
-- 비회원은 카테고리가 없으므로 회원(users)만 가진다.
CREATE TABLE settlement_user_categories (
    settlement_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    category_code VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_user_categories PRIMARY KEY (settlement_id, user_id),
    CONSTRAINT fk_settlement_user_categories_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    CONSTRAINT fk_settlement_user_categories_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_settlement_user_categories_code CHECK (
        category_code ~ '^[A-Z][A-Z_]{0,49}$'
    ),
    CONSTRAINT ck_settlement_user_categories_times CHECK (created_at <= updated_at)
);

CREATE INDEX idx_settlement_user_categories_user
    ON settlement_user_categories (user_id, settlement_id);

-- 분류 규칙 사전(STL-008-004). 사용자 화면 없이 서버 운영용으로 관리한다.
-- 가게 이름·거래명·품목명을 소문자로 바꾸고 공백을 지운 뒤, 같은 방식으로 정규화한 keyword가 들어 있으면 category_code로 분류한다.
-- 여러 개가 맞으면 priority가 큰 것, 같으면 정규화한 keyword가 긴 것, 그다음 id가 작은 것.
CREATE TABLE settlement_category_rules (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
    keyword VARCHAR(100) NOT NULL,
    category_code VARCHAR(50) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    use_yn BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_settlement_category_rules PRIMARY KEY (id),
    CONSTRAINT ck_settlement_category_rules_keyword CHECK (
        CHAR_LENGTH(BTRIM(keyword)) > 0 AND keyword = BTRIM(keyword)
    ),
    CONSTRAINT ck_settlement_category_rules_code CHECK (
        category_code ~ '^[A-Z][A-Z_]{0,49}$' AND category_code <> 'UNCATEGORIZED'
    ),
    CONSTRAINT ck_settlement_category_rules_priority CHECK (priority >= 0),
    CONSTRAINT ck_settlement_category_rules_times CHECK (created_at <= updated_at)
);

-- 정규화했을 때 같은 키워드(대소문자·공백만 다른 키워드)는 하나만 둔다.
CREATE UNIQUE INDEX uk_settlement_category_rules_normalized_keyword
    ON settlement_category_rules (LOWER(REGEXP_REPLACE(keyword, '\s', '', 'g')));

-- 초기 규칙: 기능 명세 STL-008-004 예시
INSERT INTO settlement_category_rules (keyword, category_code, priority)
VALUES
    ('스타벅스', 'CAFE_SNACK', 0),
    ('투썸플레이스', 'CAFE_SNACK', 0),
    ('메가커피', 'CAFE_SNACK', 0),
    ('CU', 'CONVENIENCE_MART', 0),
    ('GS25', 'CONVENIENCE_MART', 0),
    ('세븐일레븐', 'CONVENIENCE_MART', 0),
    ('카카오T', 'TRANSPORT_CAR', 0),
    ('티머니', 'TRANSPORT_CAR', 0),
    ('CGV', 'HOBBY_LEISURE', 0),
    ('메가박스', 'HOBBY_LEISURE', 0),
    ('넷플릭스', 'SUBSCRIPTION', 0),
    ('유튜브 프리미엄', 'SUBSCRIPTION', 0),
    ('멜론', 'SUBSCRIPTION', 0);
