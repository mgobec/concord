package com.walmartlabs.concord.server.process.queue;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2019 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.google.common.collect.ImmutableSet;
import com.walmartlabs.concord.server.sdk.ProcessStatus;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Base class for all filters that are implementing process wait conditions.
 */
public abstract class WaitProcessFinishFilter implements ProcessQueueEntryFilter {

    private static final Set<ProcessStatus> FINAL_STATUSES = ImmutableSet.of(
            ProcessStatus.FINISHED,
            ProcessStatus.FAILED,
            ProcessStatus.CANCELLED,
            ProcessStatus.TIMED_OUT);

    private final ProcessQueueDao processQueueDao;

    protected WaitProcessFinishFilter(ProcessQueueDao processQueueDao) {
        this.processQueueDao = processQueueDao;
    }

    @Override
    public boolean filter(DSLContext tx, ProcessQueueEntry item) {
        List<UUID> processes = findProcess(tx, item);
        if (processes.isEmpty()) {
            return true;
        }

        processQueueDao.updateWait(tx, item.key(), ProcessCompletionCondition.builder()
                .processes(processes)
                .reason(getReason())
                .finalStatuses(getFinalStatuses())
                .completeCondition(getCompleteCondition())
                .build());

        return false;
    }

    protected abstract List<UUID> findProcess(DSLContext tx, ProcessQueueEntry item);

    protected Set<ProcessStatus> getFinalStatuses() {
        return FINAL_STATUSES;
    }

    protected ProcessCompletionCondition.CompleteCondition getCompleteCondition() {
        return ProcessCompletionCondition.CompleteCondition.ALL;
    }

    /**
     * @return the "reason" string, explaining why this particular wait condition was added.
     */
    protected abstract String getReason();

}
