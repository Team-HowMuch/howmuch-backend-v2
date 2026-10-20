package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.RecapCategoryTotal;

public record RecapCategoryTotalResult(int rank, RecapCategoryResult category, long totalOwnAmount) {
    public static RecapCategoryTotalResult from(RecapCategoryTotal total) {
        return new RecapCategoryTotalResult(total.rank(), RecapCategoryResult.from(total.category()), total.totalOwnAmount());
    }
}
