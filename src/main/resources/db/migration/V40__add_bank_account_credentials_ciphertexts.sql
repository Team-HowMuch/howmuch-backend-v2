ALTER TABLE connected_institutions
    ADD COLUMN account_credentials_local_ciphertext TEXT,
    ADD COLUMN account_credentials_kms_ciphertext TEXT,
    ADD CONSTRAINT chk_connected_institutions_kms_requires_local
        CHECK (account_credentials_kms_ciphertext IS NULL
            OR account_credentials_local_ciphertext IS NOT NULL);

UPDATE common_code_group
SET attr3_name = '계좌번호/비밀번호 필요 여부',
    revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE group_code = 'BANK';

UPDATE common_code_detail
SET attr3 = '1',
    updated_at = CURRENT_TIMESTAMP
WHERE group_code = 'BANK'
  AND code IN ('0020', '0031')
  AND attr3 IS DISTINCT FROM '1';
