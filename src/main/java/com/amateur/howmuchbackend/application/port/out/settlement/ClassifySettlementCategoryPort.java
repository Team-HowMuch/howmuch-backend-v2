package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.List;

/**
 * 가게 이름·거래명·품목명으로 정산 카테고리를 고르는 외부 분류기(LLM).
 *
 * <p>분류기는 후보 코드 중 하나나 {@link Outcome#UNKNOWN}을 돌려준다. 후보 밖 코드를 걸러 내는 일은 호출하는 쪽 Policy가 한다.
 */
public interface ClassifySettlementCategoryPort {
    boolean available();

    Result classify(Request request);

    record Request(List<String> merchantNames, List<String> itemNames, List<Candidate> candidates) {
        public Request {
            merchantNames = List.copyOf(merchantNames);
            itemNames = List.copyOf(itemNames);
            candidates = List.copyOf(candidates);
        }
    }

    record Candidate(String code, String name, String description) {
    }

    enum Outcome {
        CLASSIFIED,
        UNKNOWN,
        PROVIDER_ERROR,
        INVALID_RESPONSE
    }

    record Result(Outcome outcome, String categoryCode) {
        public static Result classified(String categoryCode) {
            return new Result(Outcome.CLASSIFIED, categoryCode);
        }

        public static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }
}
