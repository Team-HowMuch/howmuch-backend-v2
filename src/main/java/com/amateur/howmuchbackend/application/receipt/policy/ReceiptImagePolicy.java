package com.amateur.howmuchbackend.application.receipt.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 업로드된 바이트가 정말 OCR이 읽을 수 있는 이미지인지 판정한다.
 *
 * <p>멀티파트의 Content-Type은 클라이언트가 정하는 값이라 신뢰할 수 없다. PDF나 임의
 * 바이트를 {@code image/jpeg}라고 선언해 보내면 그대로 통과해, GPU 한 대짜리 OCR 장비가
 * 읽지 못할 것이 뻔한 요청에 수 초를 쓴다. 그래서 선언값을 버리고 바이트에서 형식을
 * 알아낸다. 저장소의 {@code GroupImagePolicy}·{@code ProfileImagePolicy}가 같은 방식이다.
 *
 * <p>WebP는 받지 않는다. 내부 OCR 서비스가 JPEG·PNG만 받는다.
 */
@Component
public class ReceiptImagePolicy {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * @return 바이트에서 알아낸 실제 형식. 이 값을 OCR 요청 파트에 싣는다.
     * @throws ApplicationException JPEG·PNG가 아니면 400
     */
    public String detectMediaType(byte[] content) {
        if (content == null || content.length == 0) {
            throw invalidImage();
        }
        if (isJpeg(content)) {
            return "image/jpeg";
        }
        if (isPng(content)) {
            return "image/png";
        }
        throw invalidImage();
    }

    private boolean isJpeg(byte[] content) {
        return content.length >= 3
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] content) {
        return content.length >= PNG_SIGNATURE.length
                && Arrays.equals(Arrays.copyOf(content, PNG_SIGNATURE.length), PNG_SIGNATURE);
    }

    private ApplicationException invalidImage() {
        return ApplicationException.of(SettlementStatusCode.INVALID_RECEIPT_IMAGE);
    }
}
