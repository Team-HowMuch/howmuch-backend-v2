package com.amateur.howmuchbackend.domain.commoncode;

public record CommonCode(
        String groupCode,
        String code,
        String name,
        String description,
        int sortOrder,
        String attr1,
        String attr2,
        String attr3,
        boolean active
) {
    public CommonCode {
        requireText(groupCode, "groupCode");
        requireText(code, "code");
        requireText(name, "name");
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must not be negative");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
