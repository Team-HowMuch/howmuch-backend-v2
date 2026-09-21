package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.ReportTreasurerAnswerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.command.ReportTreasurerAnswerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerReportResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.SaveTreasurerAnswerReportPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReport;
import com.amateur.howmuchbackend.domain.treasurer.status.TreasurerStatusCode;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class ReportTreasurerAnswerService implements ReportTreasurerAnswerUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadTreasurerAnswerPort loadTreasurerAnswerPort;
    private final SaveTreasurerAnswerReportPort saveTreasurerAnswerReportPort;
    private final Clock clock;

    @Override
    @Transactional
    public TreasurerAnswerReportResult report(ReportTreasurerAnswerCommand command) {
        Long userId = currentActorPort.getCurrentActor().userId();

        // 남의 답변인지 없는 답변인지 구분해 알리지 않는다. 존재 여부가 새면 안 된다.
        // 보관 기간이 지나 지워진 답변도 같은 404다.
        loadTreasurerAnswerPort.findOwned(command.answerId(), userId)
                .orElseThrow(() -> new ApplicationException(TreasurerStatusCode.TREASURER_ANSWER_NOT_FOUND));

        TreasurerAnswerReport report = TreasurerAnswerReport.open(
                command.answerId(),
                userId,
                command.reason(),
                command.detail(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        );

        // 조회와 저장 사이에 다른 요청이 신고를 넣을 수 있다. 유니크 제약이 잡는다.
        boolean stored;
        try {
            stored = saveTreasurerAnswerReportPort.saveIfAbsent(report);
        } catch (DataIntegrityViolationException exception) {
            // 조회와 저장 사이에 보관 정리가 그 답변을 지웠다. 복합 FK가 거부한다.
            // 사용자 입장에서는 없는 답변이므로 500이 아니라 조회 실패와 같은 404다.
            throw new ApplicationException(TreasurerStatusCode.TREASURER_ANSWER_NOT_FOUND, exception);
        }
        if (!stored) {
            throw new ApplicationException(TreasurerStatusCode.TREASURER_ANSWER_ALREADY_REPORTED);
        }
        return TreasurerAnswerReportResult.from(report);
    }
}
