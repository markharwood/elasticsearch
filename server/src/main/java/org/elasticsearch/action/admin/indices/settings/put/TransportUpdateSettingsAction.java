/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.indices.settings.put;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetadataUpdateSettingsService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class TransportUpdateSettingsAction extends AcknowledgedTransportMasterNodeAction<UpdateSettingsRequest> {

    private static final Logger logger = LogManager.getLogger(TransportUpdateSettingsAction.class);

    private final MetadataUpdateSettingsService updateSettingsService;
    private final SystemIndices systemIndices;

    @Inject
    public TransportUpdateSettingsAction(TransportService transportService, ClusterService clusterService,
                                         ThreadPool threadPool, MetadataUpdateSettingsService updateSettingsService,
                                         ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                         SystemIndices systemIndices) {
        super(UpdateSettingsAction.NAME, transportService, clusterService, threadPool, actionFilters, UpdateSettingsRequest::new,
            indexNameExpressionResolver, ThreadPool.Names.SAME);
        this.updateSettingsService = updateSettingsService;
        this.systemIndices = systemIndices;
    }

    @Override
    protected ClusterBlockException checkBlock(UpdateSettingsRequest request, ClusterState state) {
        // allow for dedicated changes to the metadata blocks, so we don't block those to allow to "re-enable" it
        ClusterBlockException globalBlock = state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
        if (globalBlock != null) {
            return globalBlock;
        }
        if (request.settings().size() == 1 &&  // we have to allow resetting these settings otherwise users can't unblock an index
            IndexMetadata.INDEX_BLOCKS_METADATA_SETTING.exists(request.settings())
            || IndexMetadata.INDEX_READ_ONLY_SETTING.exists(request.settings())
            || IndexMetadata.INDEX_BLOCKS_READ_ONLY_ALLOW_DELETE_SETTING.exists(request.settings())) {
            return null;
        }
        return state.blocks().indicesBlockedException(ClusterBlockLevel.METADATA_WRITE,
            indexNameExpressionResolver.concreteIndexNames(state, request));
    }

    @Override
    protected void masterOperation(final UpdateSettingsRequest request, final ClusterState state,
                                   final ActionListener<AcknowledgedResponse> listener) {
        final Index[] concreteIndices = indexNameExpressionResolver.concreteIndices(state, request);
        final Settings requestSettings = request.settings();

        final Map<String, List<String>> systemIndexViolations = checkForSystemIndexViolations(concreteIndices, request);
        if (systemIndexViolations.isEmpty() == false) {
            final String message = "Cannot override settings on system indices: "
                + systemIndexViolations.entrySet()
                    .stream()
                    .map(entry -> "[" + entry.getKey() + "] -> " + entry.getValue())
                    .collect(Collectors.joining(", "));
            logger.warn(message);
            listener.onFailure(new IllegalStateException(message));
            return;
        }

        UpdateSettingsClusterStateUpdateRequest clusterStateUpdateRequest = new UpdateSettingsClusterStateUpdateRequest()
                .indices(concreteIndices)
                .settings(requestSettings)
                .setPreserveExisting(request.isPreserveExisting())
                .ackTimeout(request.timeout())
                .masterNodeTimeout(request.masterNodeTimeout());

        updateSettingsService.updateSettings(clusterStateUpdateRequest, new ActionListener<AcknowledgedResponse>() {
            @Override
            public void onResponse(AcknowledgedResponse response) {
                listener.onResponse(response);
            }

            @Override
            public void onFailure(Exception t) {
                logger.debug(() -> new ParameterizedMessage("failed to update settings on indices [{}]", (Object) concreteIndices), t);
                listener.onFailure(t);
            }
        });
    }

    /**
     * Checks that if the request is trying to apply settings changes to any system indices, then the settings' values match those
     * that the system index's descriptor expects.
     *
     * @param concreteIndices the indices being updated
     * @param request the update request
     * @return a mapping from system index pattern to the settings whose values would be overridden. Empty if there are no violations.
     */
    private Map<String, List<String>> checkForSystemIndexViolations(Index[] concreteIndices, UpdateSettingsRequest request) {
        // Requests that a cluster generates itself are permitted to have a difference in settings
        // so that rolling upgrade scenarios still work. We check this via the request's origin.
        if (Strings.isNullOrEmpty(request.origin()) == false) {
            return Collections.emptyMap();
        }

        final Map<String, List<String>> violationsByIndex = new HashMap<>();
        final Settings requestSettings = request.settings();

        for (Index index : concreteIndices) {
            final SystemIndexDescriptor descriptor = systemIndices.findMatchingDescriptor(index.getName());
            if (descriptor != null && descriptor.isAutomaticallyManaged()) {
                final Settings descriptorSettings = descriptor.getSettings();
                List<String> failedKeys = new ArrayList<>();
                for (String key : requestSettings.keySet()) {
                    final String expectedValue = descriptorSettings.get(key);
                    final String actualValue = requestSettings.get(key);

                    if (Objects.equals(expectedValue, actualValue) == false) {
                        failedKeys.add(key);
                    }
                }

                if (failedKeys.isEmpty() == false) {
                    violationsByIndex.put(descriptor.getIndexPattern(), failedKeys);
                }
            }
        }

        return violationsByIndex;
    }
}
