package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.RecapCategory;

public record RecapCategoryResult(String code, String name, String icon) {
    public static RecapCategoryResult from(RecapCategory category) {
        return category == null ? null : new RecapCategoryResult(category.code(), category.name(), category.icon());
    }
}
