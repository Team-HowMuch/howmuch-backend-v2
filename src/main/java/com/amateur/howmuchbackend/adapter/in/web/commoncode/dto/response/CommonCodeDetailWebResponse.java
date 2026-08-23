package com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.response;

import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeDetailResult;

public record CommonCodeDetailWebResponse(
        String code,
        String name,
        String description,
        int sortOrder,
        String attr1,
        String attr2,
        String attr3,
        boolean active
) {
    public static CommonCodeDetailWebResponse from(CommonCodeDetailResult result) {
        return new CommonCodeDetailWebResponse(
                result.code(),
                result.name(),
                result.description(),
                result.sortOrder(),
                result.attr1(),
                result.attr2(),
                result.attr3(),
                result.active()
        );
    }
}
