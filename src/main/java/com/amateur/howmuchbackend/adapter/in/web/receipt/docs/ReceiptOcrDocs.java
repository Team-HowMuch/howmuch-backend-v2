package com.amateur.howmuchbackend.adapter.in.web.receipt.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.receipt.dto.response.ReceiptOcrWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "영수증 OCR API", description = "영수증 이미지를 분석해 상호·일시·품목·합계를 반환합니다. 이미지는 저장하지 않습니다.")
public interface ReceiptOcrDocs {
    @Operation(
            summary = "영수증 이미지 분석",
            description = "영수증 사진을 multipart로 올리면 구조화된 영수증 데이터를 반환합니다. "
                    + "JPEG 또는 PNG만 받고, 업로드 한도는 5MB입니다. 분석에 수 초가 걸립니다.\n\n"
                    + "대부분의 값은 읽지 못하면 null입니다. `totalVerified`가 false면 "
                    + "합계를 사용자에게 확인받는 흐름을 권장합니다.\n\n"
                    + "이미지는 분석에만 쓰이고 저장하지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "분석 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_RECEIPT_IMAGE - 빈 파일이거나 JPEG/PNG가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "업로드 한도 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "RECEIPT_OCR_FAILED - 이미지를 읽지 못함. 다시 촬영이 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "RECEIPT_OCR_UNAVAILABLE - 분석 서비스에 연결 실패. 잠시 후 재시도")
    })
    ApiResponse<ReceiptOcrWebResponse> analyze(MultipartFile file);
}
