package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.RecapRecordResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.DayOfWeek;

@Schema(description = "완료 정산의 의외의 기록. 자료가 없는 칸은 null이다.",
        requiredProperties = {"busiestWeekday", "frequentCategory", "largestSettlement", "fastestSettlement"})
public record RecapRecordWebResponse(
        @Schema(nullable = true) BusiestWeekday busiestWeekday,
        @Schema(nullable = true) FrequentCategory frequentCategory,
        @Schema(nullable = true) LargestSettlement largestSettlement,
        @Schema(nullable = true) FastestSettlement fastestSettlement) {

    @Schema(name = "RecapBusiestWeekdayWebResponse", requiredProperties = {"weekday", "count"})
    public record BusiestWeekday(
            @Schema(description = "KST 완료 요일. 횟수 동률이면 월요일부터 선택한다.") DayOfWeek weekday,
            @Schema(description = "해당 요일에 완료한 정산 수", minimum = "1") int count) {}

    @Schema(name = "RecapFrequentCategoryWebResponse", requiredProperties = {"category", "count"})
    public record FrequentCategory(
            @Schema(description = "미분류 제외. 횟수 동률이면 카테고리 코드순으로 선택한다.") RecapCategoryWebResponse category,
            @Schema(description = "해당 카테고리로 완료한 정산 수", minimum = "1") int count) {}

    @Schema(name = "RecapLargestSettlementWebResponse", requiredProperties = {"totalOwnAmount", "category"})
    public record LargestSettlement(
            @Schema(description = "한 정산에서 가장 큰 본인 부담액, 원. 동률은 정산 ID순이다.", minimum = "0") long totalOwnAmount,
            @Schema(description = "미분류 또는 카테고리 정보가 없으면 null", nullable = true) RecapCategoryWebResponse category) {}

    @Schema(name = "RecapFastestSettlementWebResponse", requiredProperties = {"durationMinutes", "title"})
    public record FastestSettlement(
            @Schema(description = "정산 링크 생성부터 완료까지의 경과시간(중단 시간 포함). 실제 시간으로 비교한 뒤 분 미만을 버리며 1분 미만은 0이다.", minimum = "0") long durationMinutes,
            @Schema(description = "리캡 생성 당시 정산 제목") String title) {}

    public static RecapRecordWebResponse from(RecapRecordResult record) {
        if (record == null) return null;
        var busiest = record.busiestWeekday();
        var frequent = record.frequentCategory();
        var largest = record.largestSettlement();
        var fastest = record.fastestSettlement();
        return new RecapRecordWebResponse(
                busiest == null ? null : new BusiestWeekday(busiest.weekday(), busiest.count()),
                frequent == null ? null : new FrequentCategory(RecapCategoryWebResponse.from(frequent.category()), frequent.count()),
                largest == null ? null : new LargestSettlement(largest.totalOwnAmount(), RecapCategoryWebResponse.from(largest.category())),
                fastest == null ? null : new FastestSettlement(fastest.durationMinutes(), fastest.title()));
    }
}
