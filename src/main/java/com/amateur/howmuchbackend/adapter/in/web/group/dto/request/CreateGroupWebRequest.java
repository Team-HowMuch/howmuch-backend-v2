package com.amateur.howmuchbackend.adapter.in.web.group.dto.request;

import com.amateur.howmuchbackend.application.port.in.group.command.CreateGroupCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public record CreateGroupWebRequest(
        @Schema(description = "그룹 이름", example = "여행 모임")
        String name,
        @Schema(description = "그룹 메모", example = "매달 여행 경비를 정산해요")
        String memo,
        @Schema(description = "JPEG, PNG 또는 WebP 그룹 이미지", type = "string", format = "binary")
        MultipartFile image
) {
    public CreateGroupCommand toCommand() {
        return new CreateGroupCommand(name, memo, imageContent());
    }

    private byte[] imageContent() {
        if (image == null) {
            return null;
        }
        try {
            return image.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read group image upload.", exception);
        }
    }
}
