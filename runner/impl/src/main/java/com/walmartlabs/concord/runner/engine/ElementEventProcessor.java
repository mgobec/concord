package com.walmartlabs.concord.runner.engine;

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

import com.walmartlabs.concord.client.ApiClientConfiguration;
import com.walmartlabs.concord.client.ApiClientFactory;
import com.walmartlabs.concord.client.ProcessEventRequest;
import com.walmartlabs.concord.client.ProcessEventsApi;
import io.takari.bpm.ProcessDefinitionProvider;
import io.takari.bpm.ProcessDefinitionUtils;
import io.takari.bpm.api.ExecutionException;
import io.takari.bpm.model.AbstractElement;
import io.takari.bpm.model.ProcessDefinition;
import io.takari.bpm.model.SourceAwareProcessDefinition;
import io.takari.bpm.model.SourceMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public class ElementEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(ElementEventProcessor.class);

    private final ApiClientFactory apiClientFactory;
    private final ProcessDefinitionProvider processDefinitionProvider;

    public ElementEventProcessor(ApiClientFactory apiClientFactory, ProcessDefinitionProvider processDefinitionProvider) {
        this.apiClientFactory = apiClientFactory;
        this.processDefinitionProvider = processDefinitionProvider;
    }

    public void process(ElementEvent event, EventParamsBuilder builder) throws ExecutionException {
        process(event, builder, null);
    }

    public void process(ElementEvent event, EventParamsBuilder builder, Predicate<AbstractElement> filter) throws ExecutionException {

        ProcessDefinition pd = processDefinitionProvider.getById(event.getProcessDefinitionId());
        if (pd == null) {
            throw new RuntimeException("Process definition not found: " + event.getProcessDefinitionId());
        }

        if (!(pd instanceof SourceAwareProcessDefinition)) {
            return;
        }

        Map<String, SourceMap> sourceMaps = ((SourceAwareProcessDefinition) pd).getSourceMaps();

        SourceMap source = sourceMaps.get(event.getElementId());
        if (source == null) {
            return;
        }

        AbstractElement element = ProcessDefinitionUtils.findElement(pd, event.getElementId());

        if (filter != null && !filter.test(element)) {
            return;
        }

        try {
            Map<String, Object> e = new HashMap<>();
            e.put("processDefinitionId", event.getProcessDefinitionId());
            e.put("elementId", event.getElementId());
            e.put("line", source.getLine());
            e.put("column", source.getColumn());
            e.put("description", source.getDescription());
            e.putAll(builder.build(element));

            ProcessEventRequest req = new ProcessEventRequest();
            req.setEventType("ELEMENT"); // TODO should it be in the constants?
            req.setData(e);

            ProcessEventsApi client = new ProcessEventsApi(apiClientFactory.create(
                    ApiClientConfiguration.builder()
                            .sessionToken(event.getSessionToken())
                            .txId(UUID.fromString(event.getInstanceId()))
                            .build()));
            client.event(UUID.fromString(event.getInstanceId()), req);
        } catch (Exception e) {
            log.warn("process ['{}'] -> transfer error: {}", event.getInstanceId(), e.getMessage());
        }
    }

    public interface EventParamsBuilder {
        Map<String, Object> build(AbstractElement element);
    }

    public static class ElementEvent {

        private final String instanceId;
        private final String processDefinitionId;
        private final String elementId;
        private final String sessionToken;

        public ElementEvent(String instanceId, String processDefinitionId, String elementId, String sessionToken) {
            this.instanceId = instanceId;
            this.processDefinitionId = processDefinitionId;
            this.elementId = elementId;
            this.sessionToken = sessionToken;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public String getProcessDefinitionId() {
            return processDefinitionId;
        }

        public String getElementId() {
            return elementId;
        }

        public String getSessionToken() {
            return sessionToken;
        }
    }
}
