UPDATE common_code_group
SET attr3_name = '연결 인증 방식 (1=공동인증서, 2=ID/PW, 3=ID/PW+계좌정보)',
    revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE group_code = 'BANK';

UPDATE common_code_detail
SET attr3 = CASE
        WHEN code IN ('0020', '0031') THEN '3'
        WHEN code IN ('0003', '0004', '0011', '0037', '0039', '0045', '0071', '0081', '0088') THEN '2'
        ELSE '1'
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE group_code = 'BANK'
  AND use_yn = TRUE;
