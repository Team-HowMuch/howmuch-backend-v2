package com.amateur.howmuchbackend.application.port.in.receipt;

import com.amateur.howmuchbackend.application.port.in.receipt.command.AnalyzeReceiptCommand;
import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;

public interface AnalyzeReceiptUseCase {
    ReceiptAnalysisResult analyze(AnalyzeReceiptCommand command);
}
