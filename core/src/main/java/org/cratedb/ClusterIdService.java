package org.cratedb;

import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ImmutableSettings;

import static org.elasticsearch.cluster.ClusterState.builder;

public class ClusterIdService implements ClusterStateListener {

    private final ClusterService clusterService;
    private final ESLogger logger = Loggers.getLogger(ClusterIdService.class);
    private ClusterId clusterId = null;

    public static final String clusterIdSettingsKey = "cluster_id";

    @Inject
    public ClusterIdService(ClusterService clusterService) {
        this.clusterService = clusterService;

        // Add to listen for state changes
        this.clusterService.add((ClusterStateListener)this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (logger.isTraceEnabled()) {
            logger.trace("[{}] Receiving new cluster state, reason {}",
                    clusterService.state().nodes().localNodeId(), event.source());
        }
        if (event.source().equals("local-gateway-elected-state")) {
            // State recovered, read cluster_id
            boolean success = applyClusterIdFromSettings();

            if (event.localNodeMaster() && success == false) {
                // None found, generate cluster_id and broadcast it to all nodes
                generateClusterId();
                saveClusterIdToSettings();
            }
        }

        applyClusterIdFromSettings();
    }

    public ClusterId clusterId() {
        return clusterId;
    }

    private void generateClusterId() {
        if (clusterId == null) {
            clusterId = new ClusterId();

            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Generated ClusterId {}",
                        clusterService.state().nodes().localNodeId(), clusterId.value());
            }
        }
    }

    private String readClusterIdFromSettings() {
        return clusterService.state().metaData().transientSettings().get(clusterIdSettingsKey);
    }

    private boolean applyClusterIdFromSettings() {
        if (clusterId == null) {
            String id = readClusterIdFromSettings();
            if (id == null) {
                return false;
            }

            clusterId = new ClusterId(id);

            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Read ClusterId from settings {}",
                    clusterService.state().nodes().localNodeId(), clusterId.value());
            }
        }

        return true;
    }

    private void saveClusterIdToSettings() {
        if (logger.isTraceEnabled()) {
            logger.trace("Announcing new cluster_id to all nodes");
        }
        clusterService.submitStateUpdateTask("new_cluster_id", Priority.URGENT, new ClusterStateUpdateTask() {

            @Override
            public void onFailure(String source, Throwable t) {
                logger.error("failed to perform [{}]", t, source);
            }

            @Override
            public ClusterState execute(final ClusterState currentState) {
                ImmutableSettings.Builder transientSettings = ImmutableSettings.settingsBuilder();
                transientSettings.put(currentState.metaData().transientSettings());
                transientSettings.put(clusterIdSettingsKey, clusterId.value().toString());

                MetaData.Builder metaData = MetaData.builder(currentState.metaData())
                        .persistentSettings(currentState.metaData().persistentSettings())
                        .transientSettings(transientSettings.build());

                ClusterBlocks.Builder blocks = ClusterBlocks.builder().blocks(currentState.blocks());
                boolean updatedReadOnly =
                        metaData.persistentSettings().getAsBoolean(MetaData.SETTING_READ_ONLY, false)
                        || metaData.transientSettings().getAsBoolean(MetaData.SETTING_READ_ONLY, false);
                if (updatedReadOnly) {
                    blocks.addGlobalBlock(MetaData.CLUSTER_READ_ONLY_BLOCK);
                } else {
                    blocks.removeGlobalBlock(MetaData.CLUSTER_READ_ONLY_BLOCK);
                }

                return builder(currentState).metaData(metaData).blocks(blocks).build();
            }

        });

    }

}