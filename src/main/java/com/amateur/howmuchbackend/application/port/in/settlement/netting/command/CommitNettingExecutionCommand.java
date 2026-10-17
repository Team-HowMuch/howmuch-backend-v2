package com.amateur.howmuchbackend.application.port.in.settlement.netting.command;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingInput;

import java.util.UUID;

public record CommitNettingExecutionCommand(
        UUID executionId, UUID leaseToken, NettingInput snapshot) {}
