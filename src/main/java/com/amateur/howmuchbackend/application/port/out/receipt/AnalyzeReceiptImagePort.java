package com.amateur.howmuchbackend.application.port.out.receipt;

import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;

public interface AnalyzeReceiptImagePort {
    /**
     * 영수증 이미지 한 장을 구조화된 데이터로 분석한다.
     *
     * @throws com.amateur.howmuchbackend.common.exception.ApplicationException
     *         분석 서비스에 닿지 못하면 503, 이미지를 읽지 못하면 422
     */
    ReceiptAnalysisResult analyze(String fileName, String contentType, byte[] bytes);
}
