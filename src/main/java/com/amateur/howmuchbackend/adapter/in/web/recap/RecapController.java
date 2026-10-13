package com.amateur.howmuchbackend.adapter.in.web.recap;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.recap.docs.RecapDocs;
import com.amateur.howmuchbackend.adapter.in.web.recap.dto.response.*;
import com.amateur.howmuchbackend.application.port.in.recap.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/recaps")
public class RecapController implements RecapDocs {
    private final GetMyRecapMonthsUseCase months;
    private final GetMyMonthlyRecapUseCase detail;
    private final GetMySharedRecapUseCase share;
    private final GetMyRecapCompanionAvatarUseCase avatar;

    @Override @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RecapMonthsWebResponse> getMonths() {
        return ApiResponse.success(RecapMonthsWebResponse.from(months.getMonths()));
    }
    @Override @GetMapping(value = "/{yearMonth}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<MonthlyRecapWebResponse> getRecap(@PathVariable String yearMonth) {
        return ApiResponse.success(MonthlyRecapWebResponse.from(detail.getRecap(parse(yearMonth))));
    }
    @Override @GetMapping(value = "/{yearMonth}/share", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SharedMonthlyRecapWebResponse> getShare(@PathVariable String yearMonth) {
        return ApiResponse.success(SharedMonthlyRecapWebResponse.from(share.getShare(parse(yearMonth))));
    }
    @Override @GetMapping("/{yearMonth}/companion/avatar")
    public ResponseEntity<byte[]> getCompanionAvatar(@PathVariable String yearMonth) {
        var image = avatar.getCompanionAvatar(parse(yearMonth));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(image.contentType())).body(image.content());
    }

    private YearMonth parse(String text) {
        if (!text.matches("[0-9]{4}-(0[1-9]|1[0-2])") || text.startsWith("0000-")) {
            throw ApplicationException.of(CommonStatusCode.INVALID_ARGUMENT);
        }
        return YearMonth.parse(text);
    }
}
