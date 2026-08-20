-- 그룹 코드 테이블
CREATE TABLE common_code_group (
   group_code VARCHAR(50) PRIMARY KEY,
   group_name VARCHAR(100) NOT NULL,
   description VARCHAR(500),
   attr1_name VARCHAR(50),
   attr2_name VARCHAR(50),
   attr3_name VARCHAR(50);
   use_yn BOOLEAN NOT NULL DEFAULT TRUE,
   created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
   updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL
);

-- 상세 코드 테이블
CREATE TABLE common_code_detail (
    group_code VARCHAR(50) NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    sort_order INT NOT NULL DEFAULT 0,
    attr1 VARCHAR(100),
    attr2 VARCHAR(100),
    attr3 VARCHAR(100);
    use_yn BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    PRIMARY KEY (group_code, code),
    FOREIGN KEY (group_code) REFERENCES common_code_group(group_code)
);


