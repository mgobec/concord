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

import com.walmartlabs.concord.server.process.ProcessKey;
import com.walmartlabs.concord.server.queueclient.message.Imports;
import org.immutables.value.Value;

import javax.annotation.Nullable;
import java.util.UUID;

@Value.Immutable
public interface ProcessQueueEntry {

    ProcessKey key();

    @Nullable
    UUID projectId();

    @Nullable
    UUID orgId();

    @Nullable
    UUID parentInstanceId();

    @Nullable
    UUID initiatorId();

    boolean exclusive();

    @Nullable
    UUID repoId();

    @Nullable
    String repoPath();

    @Nullable
    String repoUrl();

    @Nullable
    String commitId();

    @Nullable
    Imports imports();

    static ImmutableProcessQueueEntry.Builder builder() {
        return ImmutableProcessQueueEntry.builder();
    }
}
