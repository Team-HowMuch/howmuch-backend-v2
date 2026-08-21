-- attr1: 각 기관 공식 도메인의 favicon URL
INSERT INTO common_code_group (
    group_code,
    group_name,
    description,
    attr1_name,
    use_yn
)
VALUES (
    'BANK',
    '은행',
    'CODEF 은행 기관코드와 아이콘 URL',
    'url',
    TRUE
)
ON CONFLICT (group_code) DO UPDATE
SET group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    attr1_name = EXCLUDED.attr1_name,
    use_yn = EXCLUDED.use_yn,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO common_code_detail (
    group_code,
    code,
    name,
    sort_order,
    attr1,
    use_yn
)
VALUES
    ('BANK', '0002', '산업은행', 1, 'https://www.google.com/s2/favicons?domain=kdb.co.kr&sz=128', TRUE),
    ('BANK', '0003', '기업은행', 2, 'https://www.google.com/s2/favicons?domain=ibk.co.kr&sz=128', TRUE),
    ('BANK', '0004', '국민은행', 3, 'https://www.google.com/s2/favicons?domain=kbstar.com&sz=128', TRUE),
    ('BANK', '0007', '수협은행', 4, 'https://www.google.com/s2/favicons?domain=suhyup-bank.com&sz=128', TRUE),
    ('BANK', '0011', '농협은행', 5, 'https://www.google.com/s2/favicons?domain=banking.nonghyup.com&sz=128', TRUE),
    ('BANK', '0020', '우리은행', 6, 'https://www.google.com/s2/favicons?domain=wooribank.com&sz=128', TRUE),
    ('BANK', '0023', 'SC은행', 7, 'https://www.google.com/s2/favicons?domain=sc.co.kr&sz=128', TRUE),
    ('BANK', '0027', '씨티은행', 8, 'https://www.google.com/s2/favicons?domain=citibank.co.kr&sz=128', TRUE),
    ('BANK', '0031', '대구은행', 9, 'https://www.google.com/s2/favicons?domain=imbank.co.kr&sz=128', TRUE),
    ('BANK', '0032', '부산은행', 10, 'https://www.google.com/s2/favicons?domain=busanbank.co.kr&sz=128', TRUE),
    ('BANK', '0034', '광주은행', 11, 'https://www.google.com/s2/favicons?domain=kjbank.com&sz=128', TRUE),
    ('BANK', '0035', '제주은행', 12, 'https://www.google.com/s2/favicons?domain=jejubank.co.kr&sz=128', TRUE),
    ('BANK', '0037', '전북은행', 13, 'https://www.google.com/s2/favicons?domain=jbbank.co.kr&sz=128', TRUE),
    ('BANK', '0039', '경남은행', 14, 'https://www.google.com/s2/favicons?domain=knbank.co.kr&sz=128', TRUE),
    ('BANK', '0045', '새마을금고', 15, 'https://www.google.com/s2/favicons?domain=kfcc.co.kr&sz=128', TRUE),
    ('BANK', '0048', '신협은행', 16, 'https://www.google.com/s2/favicons?domain=cu.co.kr&sz=128', TRUE),
    ('BANK', '0071', '우체국', 17, 'https://www.google.com/s2/favicons?domain=epostbank.go.kr&sz=128', TRUE),
    ('BANK', '0081', 'KEB하나은행', 18, 'https://www.google.com/s2/favicons?domain=kebhana.com&sz=128', TRUE),
    ('BANK', '0088', '신한은행', 19, 'https://www.google.com/s2/favicons?domain=shinhan.com&sz=128', TRUE),
    ('BANK', '0089', 'K뱅크', 20, 'https://www.google.com/s2/favicons?domain=kbanknow.com&sz=128', TRUE)
ON CONFLICT (group_code, code) DO UPDATE
SET name = EXCLUDED.name,
    sort_order = EXCLUDED.sort_order,
    attr1 = EXCLUDED.attr1,
    use_yn = EXCLUDED.use_yn,
    updated_at = CURRENT_TIMESTAMP;
