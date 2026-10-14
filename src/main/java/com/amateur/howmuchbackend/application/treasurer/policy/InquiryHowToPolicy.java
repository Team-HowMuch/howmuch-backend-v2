package com.amateur.howmuchbackend.application.treasurer.policy;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 질문이 사용법을 묻는 말투인지 본다.
 *
 * <p>"송금은 어떻게 해?"는 "송금"이라는 단어 때문에 키워드 판별에서 보낼 정산 조회로 걸린다. 이런 질문은
 * 데이터를 조회하기 전에 안내 쪽에 먼저 물어본다.
 *
 * <p>"보낼 돈 어떻게 돼?", "보낼 돈 뭐야?", "왜 이렇게 많아?"는 내 금액을 묻는 흔한 말투다. 여기에 걸리면
 * 데이터 질문마다 LLM을 한 번 더 부르고, 안내가 엉뚱하게 사용법을 답할 수도 있다. 그래서 "어떻게" 단독,
 * "뭐야", "왜"는 넣지 않고 무엇을 하는 방법을 묻는 표현만 둔다.
 */
@Component
public class InquiryHowToPolicy {
    private static final List<String> MARKERS = List.of(
            "어떻게해", "어떻게하", "어떻게써", "어떻게사용", "어떻게만들", "어떻게보내", "어떻게바꾸",
            "어떻게등록", "어떻게나가", "어떻게지우", "어떻게취소", "방법", "려면", "하는법", "무슨뜻",
            "뭐가달라", "차이가", "할수있", "가능해", "가능한가", "되나요", "어디서", "어디에서",
            "안돼", "안되", "안와", "안떠", "안보여"
    );

    public boolean asksHowTo(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String compact = question.replaceAll("\\s+", "");
        for (String marker : MARKERS) {
            if (compact.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
