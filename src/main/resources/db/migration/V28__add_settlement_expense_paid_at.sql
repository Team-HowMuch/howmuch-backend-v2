ALTER TABLE settlement_expenses
    ADD COLUMN paid_at TIMESTAMP(6) WITHOUT TIME ZONE;

COMMENT ON COLUMN settlement_expenses.paid_at IS
    '영수증에 찍힌 실제 결제 시각. 등록 시각(created_at)과 다르며 알 수 없으면 NULL.';
