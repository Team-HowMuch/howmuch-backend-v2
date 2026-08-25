package com.amateur.howmuchbackend.adapter.in.web.group.dto.request;

import com.amateur.howmuchbackend.application.port.in.group.command.UpdateGroupCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public class UpdateGroupWebRequest {
    private String name;
    private String memo;
    private boolean memoProvided;
    private MultipartFile image;
    private Boolean resetImage;

    @Schema(description = "변경할 그룹 이름", example = "여행 모임 2")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema(description = "변경할 메모. 빈 문자열이면 메모를 제거합니다.")
    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
        this.memoProvided = true;
    }

    @Schema(description = "새 JPEG, PNG 또는 WebP 이미지", type = "string", format = "binary")
    public MultipartFile getImage() {
        return image;
    }

    public void setImage(MultipartFile image) {
        this.image = image;
    }

    @Schema(description = "기존 이미지를 기본 이미지 상태로 초기화", example = "false")
    public Boolean getResetImage() {
        return resetImage;
    }

    public void setResetImage(Boolean resetImage) {
        this.resetImage = resetImage;
    }

    public UpdateGroupCommand toCommand() {
        return new UpdateGroupCommand(
                name,
                memo,
                memoProvided,
                imageContent(),
                Boolean.TRUE.equals(resetImage)
        );
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
