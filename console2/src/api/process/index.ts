/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
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

import { SemanticCOLORS } from 'semantic-ui-react';
import { ConcordId, ConcordKey, fetchJson, managedFetch, queryParams } from '../common';
import { ColumnDefinition } from '../org';

export enum ProcessStatus {
    PREPARING = 'PREPARING',
    ENQUEUED = 'ENQUEUED',
    STARTING = 'STARTING',
    RUNNING = 'RUNNING',
    SUSPENDED = 'SUSPENDED',
    RESUMING = 'RESUMING',
    FINISHED = 'FINISHED',
    FAILED = 'FAILED',
    CANCELLED = 'CANCELLED',
    TIMED_OUT = 'TIMED_OUT'
}

export const getStatusSemanticColor = (status: ProcessStatus): SemanticCOLORS => {
    switch (status) {
        case ProcessStatus.PREPARING:
        case ProcessStatus.RUNNING:
        case ProcessStatus.STARTING:
        case ProcessStatus.SUSPENDED:
            return 'blue';
        case ProcessStatus.FINISHED:
            return 'green';
        case ProcessStatus.CANCELLED:
        case ProcessStatus.FAILED:
        case ProcessStatus.TIMED_OUT:
            return 'red';
        case ProcessStatus.ENQUEUED:
        case ProcessStatus.RESUMING:
        default:
            return 'grey';
    }
};

export const isFinal = (s: ProcessStatus) =>
    s === ProcessStatus.FINISHED ||
    s === ProcessStatus.FAILED ||
    s === ProcessStatus.CANCELLED ||
    s === ProcessStatus.TIMED_OUT;

export const hasState = (s: ProcessStatus) => s !== ProcessStatus.PREPARING;

export const canBeCancelled = (s: ProcessStatus) =>
    s === ProcessStatus.ENQUEUED || s === ProcessStatus.RUNNING || s === ProcessStatus.SUSPENDED;

export interface ProcessCheckpointEntry {
    id: string;
    name: string;
    createdAt: string;
}

export interface ProcessHistoryPayload {
    checkpointId?: string;
}

export interface ProcessHistoryEntry {
    id: ConcordId;
    payload?: ProcessHistoryPayload;
    status: ProcessStatus;
    changeDate: string;
}

export enum WaitType {
    NONE = 'NONE',
    PROCESS_COMPLETION = 'PROCESS_COMPLETION',
    PROCESS_LOCK = 'PROCESS_LOCK'
}

export interface ProcessWaitPayload {
    processes: ConcordId[];
}

export interface ProcessLockPayload {
    instanceId: ConcordId;
    name: string;
    scope: string;
}

export type WaitPayload = ProcessWaitPayload | ProcessLockPayload;

export interface ProcessWaitHistoryEntry {
    id: ConcordId;
    eventDate: string;
    type: WaitType;
    reason?: string;
    payload?: WaitPayload;
}

export interface ProcessEntry {
    instanceId: ConcordId;
    parentInstanceId?: ConcordId;
    status: ProcessStatus;
    orgName?: ConcordKey;
    projectName?: ConcordKey;
    repoName?: ConcordKey;
    repoUrl?: string;
    repoPath?: string;
    commitId?: string;
    commitMsg?: string;
    initiator: string;
    createdAt: string;
    lastUpdatedAt: string;
    meta?: {};
    checkpoints?: ProcessCheckpointEntry[];
    statusHistory?: ProcessHistoryEntry[];
}

export interface StartProcessResponse {
    ok: boolean;
    instanceId: string;
}

export interface RestoreProcessResponse {
    ok: boolean;
}

export const start = (
    orgName: ConcordKey,
    projectName: ConcordKey,
    repoName: ConcordKey,
    entryPoint?: string,
    profile?: string
): Promise<StartProcessResponse> => {
    const data = new FormData();

    data.append('org', orgName);
    data.append('project', projectName);
    data.append('repo', repoName);
    if (entryPoint) {
        data.append('entryPoint', entryPoint);
    }
    if (profile) {
        data.append('activeProfiles', profile);
    }

    const opts = {
        method: 'POST',
        body: data
    };

    return fetchJson('/api/v1/process', opts);
};

export type ProcessDataInclude = Array<'checkpoints' | 'history' | 'childrenIds'>;

export const get = (instanceId: ConcordId, includes: ProcessDataInclude): Promise<ProcessEntry> => {
    const params = new URLSearchParams();
    includes.forEach((i) => params.append('include', i));
    return fetchJson(`/api/v2/process/${instanceId}?${params.toString()}`);
};

export const kill = (instanceId: ConcordId): Promise<{}> =>
    managedFetch(`/api/v1/process/${instanceId}`, { method: 'DELETE' });

export const killBulk = (instanceIds: ConcordId[]): Promise<{}> => {
    const opts = {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(instanceIds)
    };
    return managedFetch('/api/v1/process/bulk', opts);
};

export interface ColumnFilter {
    column: ColumnDefinition;
    filter: string;
}

// TODO remove, use ProcessListQuery everywhere
export interface ProcessFilters {
    [source: string]: string;
}

export interface PaginatedProcessEntries {
    items: ProcessEntry[];
    next?: number;
    prev?: number;
}
export interface ProcessListQuery {
    orgId?: ConcordId;
    orgName?: ConcordKey;
    projectId?: ConcordId;
    projectName?: ConcordKey;
    afterCreatedAt?: string;
    beforeCreatedAt?: string;
    tags?: string[];
    status?: ProcessStatus;
    initiator?: string;
    parentInstanceId?: ConcordId;
    meta?: ProcessFilters;
    include?: ProcessDataInclude[];
    limit?: number;
    offset?: number;
}

export const list = async (
    q: ProcessListQuery,
    includes?: ProcessDataInclude
): Promise<PaginatedProcessEntries> => {
    const { limit = 50 } = q;
    const requestLimit = limit + 1;

    const includeParams = new URLSearchParams();
    if (includes) {
        includes.forEach((i) => includeParams.append('include', i));
    }

    const filters = combine(q, { meta: nonEmpty(q.meta), limit: requestLimit });
    const qp = filters ? '&' + queryParams(filters) : '';

    const data: ProcessEntry[] = await fetchJson(
        `/api/v2/process?${includeParams.toString()}${qp}`
    );

    const hasMoreElements: boolean = !!limit && data.length > limit;
    const offset: number = q.offset ? q.offset : 0;

    if (hasMoreElements) {
        data.pop();
    }

    const nextOffset = offset + limit;
    const prevOffset = offset - limit;
    const onFirstPage = offset === 0;

    const nextPage = hasMoreElements ? nextOffset : undefined;
    const prevPage = !onFirstPage ? prevOffset : undefined;

    return {
        items: data,
        next: nextPage,
        prev: prevPage
    };
};

const nonEmpty = (values?: any) => {
    if (values === undefined) {
        return undefined;
    }

    if (Object.keys(values).length === 0) {
        return undefined;
    }

    return values;
};

// TODO refactor as a vararg function?
const combine = (values?: any, overrides?: any) => {
    if (values === undefined && overrides === undefined) {
        return undefined;
    }

    type Result = { [name: string]: any };
    const result: Result = {};

    if (values !== undefined) {
        Object.keys(values)
            .filter((k) => k !== undefined)
            .forEach((key) => (result[key] = values[key]));
    }

    if (overrides !== undefined) {
        Object.keys(overrides)
            .filter((k) => k !== undefined)
            .forEach((key) => (result[key] = overrides[key]));
    }

    return result;
};
