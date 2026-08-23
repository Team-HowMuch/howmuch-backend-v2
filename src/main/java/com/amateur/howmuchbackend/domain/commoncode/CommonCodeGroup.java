package com.amateur.howmuchbackend.domain.commoncode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record CommonCodeGroup(
        String groupCode,
        String groupName,
        String description,
        String attr1Name,
        String attr2Name,
        String attr3Name,
        boolean active,
        long revision,
        List<CommonCode> details
) {
    public CommonCodeGroup {
        requireText(groupCode, "groupCode");
        requireText(groupName, "groupName");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }

        details = details == null ? List.of() : List.copyOf(details);
        Set<String> codes = new HashSet<>();
        for (CommonCode detail : details) {
            if (!groupCode.equals(detail.groupCode())) {
                throw new IllegalArgumentException("detail groupCode must match groupCode");
            }
            if (!codes.add(detail.code())) {
                throw new IllegalArgumentException("detail code must be unique");
            }
        }
    }

    public static CommonCodeGroup create(
            String groupCode,
            String groupName,
            String description,
            String attr1Name,
            String attr2Name,
            String attr3Name,
            boolean active
    ) {
        return new CommonCodeGroup(
                groupCode,
                groupName,
                description,
                attr1Name,
                attr2Name,
                attr3Name,
                active,
                0L,
                List.of()
        );
    }

    public CommonCodeGroup update(
            String groupName,
            String description,
            String attr1Name,
            String attr2Name,
            String attr3Name,
            boolean active,
            List<CommonCode> details
    ) {
        return new CommonCodeGroup(
                groupCode,
                groupName,
                description,
                attr1Name,
                attr2Name,
                attr3Name,
                active,
                revision,
                details
        );
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
