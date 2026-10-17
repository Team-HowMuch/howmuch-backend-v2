package com.amateur.howmuchbackend.application.port.in.settlement.netting.result;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingInput;

public record NettingSynchronizationResult(NettingInput snapshot) {}
