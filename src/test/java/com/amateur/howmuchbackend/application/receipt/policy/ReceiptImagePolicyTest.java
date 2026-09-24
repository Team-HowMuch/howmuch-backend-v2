package com.amateur.howmuchbackend.application.receipt.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("영수증 이미지 판정")
class ReceiptImagePolicyTest {
    private final ReceiptImagePolicy policy = new ReceiptImagePolicy();

    private static byte[] withPrefix(byte[] prefix, int length) {
        byte[] content = new byte[Math.max(length, prefix.length)];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        return content;
    }

    static byte[] jpeg() {
        return withPrefix(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 64);
    }

    static byte[] png() {
        return withPrefix(new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 64);
    }

    @Test
    @DisplayName("JPEG 바이트는 image/jpeg로 판정한다")
    void receiptImage_jpegBytes_detectedAsImageJpeg() {
        assertThat(policy.detectMediaType(jpeg())).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("PNG 바이트는 image/png로 판정한다")
    void receiptImage_pngBytes_detectedAsImagePng() {
        assertThat(policy.detectMediaType(png())).isEqualTo("image/png");
    }

    @Test
    @DisplayName("PDF를 image/jpeg라고 선언해도 바이트를 보고 거절한다")
    void receiptImage_pdfClaimedAsJpeg_rejectedBySignature() {
        // 멀티파트 Content-Type은 요청자가 정하므로 바이트로 이미지 형식을 판별한다.
        // 선언을 신뢰하면 PDF를 OCR로 보내 GPU 자원을 낭비한다.
        byte[] pdf = "%PDF-1.7\nfake".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> policy.detectMediaType(pdf))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.INVALID_RECEIPT_IMAGE);
    }

    @Test
    @DisplayName("임의 바이트는 거절한다")
    void receiptImage_arbitraryBytes_rejected() {
        assertThatThrownBy(() -> policy.detectMediaType("not-an-image".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    @DisplayName("WebP는 받지 않는다. OCR 서비스가 JPEG·PNG만 받는다")
    void receiptImage_webp_unsupportedByOcrContract() {
        byte[] webp = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);

        assertThatThrownBy(() -> policy.detectMediaType(webp))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    @DisplayName("비었거나 없으면 거절한다")
    void receiptImage_emptyOrMissing_rejected() {
        assertThatThrownBy(() -> policy.detectMediaType(new byte[0]))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> policy.detectMediaType(null))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    @DisplayName("시그니처보다 짧은 바이트에서 터지지 않는다")
    void receiptImage_truncatedSignature_rejectedWithoutException() {
        assertThatThrownBy(() -> policy.detectMediaType(new byte[]{(byte) 0xFF, (byte) 0xD8}))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> policy.detectMediaType(new byte[]{(byte) 0x89, 0x50}))
                .isInstanceOf(ApplicationException.class);
    }
}
