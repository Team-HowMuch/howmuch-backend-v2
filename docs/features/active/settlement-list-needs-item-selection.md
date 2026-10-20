# Feature Plan: 정산 목록 품목 선택 필요 표시

- Status: In Progress
- Last updated: 2026-10-21
- Related issue: [#615](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/615)
- Related ADR: N/A

## 1. 목적

#452에서 품목 정산의 `REQUESTED`·`SUSPENDED` 상태에 본인 Share가 없으면 PAY 목록의 `amountToPay`에
정산 총액을 내려주기로 했다. 앱은 "아직 안 골랐다"를 `amountToPay <= 0`으로 판정해 왔는데, 이 보정 때문에
막 요청된 영수증 정산이 "영수증 선택 / 선택하기" 대신 "총액 / 바로 보내기"로 그려지고, 바로 보내기는
Share가 없어 404로 끝난다. 앱이 총액 표시값과 확정 개인 금액을 구분할 수 있게 목록 항목에 사실을 그대로 알린다.

- [x] `GET /api/v2/settlements` PAY 항목과 `GET /api/v2/groups/{groupId}/settlements` PARTICIPANT 항목에
      `needsItemSelection`을 제공한다. 총액 표시값을 쓸 때만 true다.
- [x] RECEIVE 탭·REQUESTER·NONE·더치페이·Share가 있는 품목 정산은 false다.
- [x] 기존 `amountToPay` 값과 계약은 바꾸지 않는다.
- 제외: 상세 응답, 송금 안내 규칙, 앱 UI 변경(프론트 #409 브랜치에서 소비).

## 2. 도메인 분석

기존 `settlement` 도메인과 `GetSettlementsUseCase`·`GetGroupSettlementsUseCase`를 재사용한다.
"품목 선택 필요"는 새 상태가 아니라, 목록 서비스가 이미 쓰는 "본인 Share 없음 + 품목 정산 선택 대기·중단"
조건의 다른 이름이다. 행위자는 로그인한 연결 참여자다.

## 3. 시나리오

1. 6,000원 영수증 정산이 요청되고 참여자 A는 아직 품목을 안 골랐다.
2. A가 PAY 목록을 조회하면 `amountToPay = 6000`, `needsItemSelection = true`다.
3. A가 품목을 골라 Share 2,000원이 생기면 `amountToPay = 2000`, `needsItemSelection = false`다.
4. 그룹 목록에서도 A는 PARTICIPANT로 같은 값을 받고, 요청자와 제3자는 false다.

경계: 더치페이는 상태와 무관하게 false다. `AWAITING_DEPOSIT`·`COMPLETED`의 Share 부재는 기존처럼 null 금액이며 false다.

## 4. 계약

```text
GET /api/v2/settlements (인증 필요)
SettlementListReadModel -> SettlementListItemResult.needsItemSelection -> SettlementListItemWebResponse.needsItemSelection
GET /api/v2/groups/{groupId}/settlements (인증 필요)
GroupSettlementListReadModel -> GroupSettlementListItemResult.needsItemSelection -> GroupSettlementListItemWebResponse.needsItemSelection
```

- 두 응답 모두 `needsItemSelection`은 required boolean이다.
- 새 UseCase·Command·Output Port·Policy·Persistence 변경은 없다. 목록 서비스의 기존 총액 보정 조건을 한 helper로 모아 금액과 플래그가 같은 사실을 보게 한다.
- Flyway migration required: **No**

## 5. 설계 결정

`amountToPay`의 의미를 바꾸지 않고 플래그를 더한다. #452 이후 총액 표시값에 의존하는 클라이언트(위젯 합계 등)가 있을 수 있고,
null로 되돌리면 중단 정산이 0원으로 보이던 #452 이전 문제가 재발한다. 플래그는 Service가 이미 아는 사실이라 추가 조회가 없다.

## 6. 영향 범위

| 영역 | 변경 |
|---|---|
| Application | `SettlementListService`·`GroupSettlementListService`가 `needsItemSelection` 계산, Result 레코드 필드 추가 |
| Web API | 두 목록 응답에 required boolean `needsItemSelection` 추가, Swagger 설명 |
| 검증 | `SettlementListServiceTest` 상태·배분 유형·Share 유무별 플래그, OpenAPI required 계약 |
| 문서 | 이 계획, `settlement-client-contract.md`, `group-settlement-list.md` |

## 7. 구현 계획과 커밋 경계

- [x] feat(settlement): 정산 목록 결과에 needsItemSelection 추가
- [x] feat(settlement): 그룹 정산 목록 결과에 needsItemSelection 추가
- [x] docs(settlement): 목록 계약 문서에 needsItemSelection 반영

## 8. 검증

- `SettlementListServiceTest`(24건)·`SettlementControllerTest`(39건) 로컬 통과. `SettlementOpenApiIntegrationTest`는 Docker가 필요해 CI에서 확인한다.
