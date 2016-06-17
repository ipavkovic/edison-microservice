package de.otto.edison.jobs.repository.cleanup;

import de.otto.edison.jobs.domain.JobInfo;
import de.otto.edison.jobs.domain.JobMessage;
import de.otto.edison.jobs.domain.Level;
import de.otto.edison.jobs.repository.JobRepository;
import de.otto.edison.jobs.service.JobMutexHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import static de.otto.edison.jobs.domain.JobMessage.jobMessage;
import static java.lang.String.format;
import static java.time.OffsetDateTime.now;

public class StopDeadJobs implements JobCleanupStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(StopDeadJobs.class);
    private static final long STOP_DEAD_JOBS_CLEANUP_INTERVAL = 10L * 60L * 1000L;

    private final int stopJobAfterSeconds;
    private final Clock clock;
    private JobRepository jobRepository;
    private JobMutexHandler jobMutexHandler;

    public StopDeadJobs(final int stopJobAfterSeconds, final Clock clock) {
        this.stopJobAfterSeconds = stopJobAfterSeconds;
        this.clock = clock;
        LOG.info("Mark old as stopped after '{}' seconds of inactivity", stopJobAfterSeconds);
    }

    @Autowired
    public void setJobRepository(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Autowired
    public void setJobMutexHandler(JobMutexHandler jobMutexHandler) {
        this.jobMutexHandler = jobMutexHandler;
    }

    @Override
    @Scheduled(fixedRate = STOP_DEAD_JOBS_CLEANUP_INTERVAL)
    public void doCleanUp() {
        OffsetDateTime now = now(clock);
        OffsetDateTime timeToMarkJobAsStopped = now.minusSeconds(stopJobAfterSeconds);
        LOG.info(format("JobCleanup: Looking for jobs older than %s ", timeToMarkJobAsStopped));
        final List<JobInfo> deadJobs = jobRepository.findRunningWithoutUpdateSince(timeToMarkJobAsStopped);
        deadJobs.forEach((j) -> {
            OffsetDateTime nowJobInfo = OffsetDateTime.now(j.getClock());
            jobRepository.createOrUpdate(j.copy()
                    .setStopped(nowJobInfo)
                    .setLastUpdated(nowJobInfo)
                    .setStatus(JobInfo.JobStatus.DEAD)
                    .build());
            jobRepository.appendMessage(j.getJobId(), jobMessage(Level.WARNING, "Job didn't receive updates for a while, considering it dead", nowJobInfo));
            jobMutexHandler.jobHasStopped(j.getJobType());
        });
    }
}
