package com.amateur.howmuchbackend.adapter.out.batch.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingJobLauncherPort;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettingJobLauncherAdapter implements NettingJobLauncherPort {
    private final JobOperator operator;
    private final Job job;

    public NettingJobLauncherAdapter(JobOperator operator, @Qualifier("groupNettingJob") Job job) {
        this.operator = operator;
        this.job = job;
    }

    public void launch(UUID id, UUID leaseToken) {
        try {
            var execution =
                    operator.start(
                            job,
                            new JobParametersBuilder()
                                    .addString("executionId", id.toString())
                                    .addString("leaseToken", leaseToken.toString())
                                    .toJobParameters());
            if (execution.getStatus() != org.springframework.batch.core.BatchStatus.COMPLETED)
                throw new IllegalStateException("NETTING_BATCH_FAILED");
        } catch (Exception failure) {
            throw new IllegalStateException("NETTING_BATCH_LAUNCH_FAILED");
        }
    }
}
