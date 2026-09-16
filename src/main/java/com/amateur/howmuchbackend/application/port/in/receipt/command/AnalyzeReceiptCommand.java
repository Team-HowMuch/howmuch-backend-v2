package com.amateur.howmuchbackend.application.port.in.receipt.command;

/**
 * 분석할 영수증 이미지.
 *
 * <p>바이트를 그대로 들고 다닌다. 저장하지 않으므로 파일 경로나 식별자가 없다.
 */
public record AnalyzeReceiptCommand(
        String fileName,
        String contentType,
        byte[] bytes
) {
}
