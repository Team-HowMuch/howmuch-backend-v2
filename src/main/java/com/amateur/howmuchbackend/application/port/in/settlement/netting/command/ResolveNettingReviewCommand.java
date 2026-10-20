package com.amateur.howmuchbackend.application.port.in.settlement.netting.command;

import java.util.UUID;

public record ResolveNettingReviewCommand(String decision, UUID transferId, String proof) {}
