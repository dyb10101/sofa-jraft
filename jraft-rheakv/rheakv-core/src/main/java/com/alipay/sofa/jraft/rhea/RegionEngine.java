/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft.rhea;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.remoting.rpc.RpcServer;
import com.alipay.sofa.jraft.Lifecycle;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.RouteTable;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.rhea.metadata.Region;
import com.alipay.sofa.jraft.rhea.options.RegionEngineOptions;
import com.alipay.sofa.jraft.rhea.storage.KVStoreStateMachine;
import com.alipay.sofa.jraft.rhea.storage.MetricsRawKVStore;
import com.alipay.sofa.jraft.rhea.storage.RaftRawKVStore;
import com.alipay.sofa.jraft.rhea.storage.RawKVStore;
import com.alipay.sofa.jraft.rhea.util.Strings;
import com.alipay.sofa.jraft.rhea.util.ThrowUtil;
import com.alipay.sofa.jraft.util.Endpoint;
import com.alipay.sofa.jraft.util.Requires;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Slf4jReporter;

/**
 * Minimum execution/copy unit of RheaKVStore.
 *
 * @author jiachun.fjc
 */
public class RegionEngine implements Lifecycle<RegionEngineOptions> {

    private static final Logger LOG = LoggerFactory.getLogger(RegionEngine.class);

    private final Region        region;
    private final StoreEngine   storeEngine;

    private RaftRawKVStore      raftRawKVStore;
    private MetricsRawKVStore   metricsRawKVStore;
    private RaftGroupService    raftGroupService;
    private Node                node;
    private KVStoreStateMachine fsm;
    private RegionEngineOptions regionOpts;

    private ScheduledReporter   regionMetricsReporter;

    private boolean             started;

    public RegionEngine(Region region, StoreEngine storeEngine) {
        this.region = region;
        this.storeEngine = storeEngine;
    }

    @Override
    public synchronized boolean init(final RegionEngineOptions opts) {
        if (this.started) {
            LOG.info("[RegionEngine: {}] already started.", this.region);
            return true;
        }
        this.regionOpts = Requires.requireNonNull(opts, "opts");
        this.fsm = new KVStoreStateMachine(this.region.getId(), this.storeEngine);

        // node options
        NodeOptions nodeOpts = opts.getNodeOptions();
        if (nodeOpts == null) {
            nodeOpts = new NodeOptions();
        }
        final long metricsReportPeriod = opts.getMetricsReportPeriod();
        if (metricsReportPeriod > 0) {
            // metricsReportPeriod > 0 means enable metrics
            nodeOpts.setEnableMetrics(true);
        }
        nodeOpts.setInitialConf(new Configuration(JRaftHelper.toJRaftPeerIdList(this.region.getPeers())));
        nodeOpts.setFsm(this.fsm);
        final String raftDataPath = opts.getRaftDataPath();
        try {
            FileUtils.forceMkdir(new File(raftDataPath));
        } catch (final Throwable t) {
            LOG.error("Fail to make dir for raftDataPath {}.", raftDataPath);
            return false;
        }
        if (Strings.isBlank(nodeOpts.getLogUri())) {
            final Path logUri = Paths.get(raftDataPath, "log");
            nodeOpts.setLogUri(logUri.toString());
        }
        if (Strings.isBlank(nodeOpts.getRaftMetaUri())) {
            final Path meteUri = Paths.get(raftDataPath, "meta");
            nodeOpts.setRaftMetaUri(meteUri.toString());
        }
        if (Strings.isBlank(nodeOpts.getSnapshotUri())) {
            final Path snapshotUri = Paths.get(raftDataPath, "snapshot");
            nodeOpts.setSnapshotUri(snapshotUri.toString());
        }
        LOG.info("[RegionEngine: {}], log uri: {}, raft meta uri: {}, snapshot uri: {}.", this.region,
            nodeOpts.getLogUri(), nodeOpts.getRaftMetaUri(), nodeOpts.getSnapshotUri());
        final Endpoint serverAddress = opts.getServerAddress();
        final PeerId serverId = new PeerId(serverAddress, 0);
        final RpcServer rpcServer = this.storeEngine.getRpcServer();
        this.raftGroupService = new RaftGroupService(opts.getRaftGroupId(), serverId, nodeOpts, rpcServer, true);
        this.node = this.raftGroupService.start(false);
        RouteTable.getInstance().updateConfiguration(this.raftGroupService.getGroupId(), nodeOpts.getInitialConf());
        if (this.node != null) {
            final RawKVStore rawKVStore = this.storeEngine.getRawKVStore();
            final Executor readIndexExecutor = this.storeEngine.getReadIndexExecutor();
            this.raftRawKVStore = new RaftRawKVStore(this.node, rawKVStore, readIndexExecutor);
            this.metricsRawKVStore = new MetricsRawKVStore(this.region.getId(), this.raftRawKVStore);
            // metrics config
            if (this.regionMetricsReporter == null && metricsReportPeriod > 0) {
                final MetricRegistry metricRegistry = this.node.getNodeMetrics().getMetricRegistry();
                if (metricRegistry != null) {
                    final ScheduledExecutorService scheduler = this.storeEngine.getMetricsScheduler();
                    // start raft node metrics reporter
                    this.regionMetricsReporter = Slf4jReporter.forRegistry(metricRegistry) //
                        .prefixedWith("region_" + String.valueOf(this.region.getId())) //
                        .withLoggingLevel(Slf4jReporter.LoggingLevel.INFO) //
                        .outputTo(LOG) //
                        .scheduleOn(scheduler) //
                        .shutdownExecutorOnStop(scheduler != null) //
                        .build();
                    this.regionMetricsReporter.start(metricsReportPeriod, TimeUnit.SECONDS);
                }
            }
            this.started = true;
            LOG.info("[RegionEngine] start successfully: {}.", this);
        }
        return this.started;
    }

    @Override
    public synchronized void shutdown() {
        if (!this.started) {
            return;
        }
        if (this.raftGroupService != null) {
            this.raftGroupService.shutdown();
            try {
                this.raftGroupService.join();
            } catch (final InterruptedException e) {
                ThrowUtil.throwException(e);
            }
        }
        if (this.regionMetricsReporter != null) {
            this.regionMetricsReporter.stop();
        }
        this.started = false;
        LOG.info("[RegionEngine] shutdown successfully: {}.", this);
    }

    public boolean transferLeadershipTo(final Endpoint endpoint) {
        final PeerId peerId = new PeerId(endpoint, 0);
        final Status status = this.node.transferLeadershipTo(peerId);
        final boolean isOk = status.isOk();
        if (isOk) {
            LOG.info("Transfer-leadership succeeded: [{} --> {}].", this.storeEngine.getSelfEndpoint(), endpoint);
        } else {
            LOG.error("Transfer-leadership failed: {}, [{} --> {}].", status, this.storeEngine.getSelfEndpoint(),
                endpoint);
        }
        return isOk;
    }

    public Region getRegion() {
        return region;
    }

    public StoreEngine getStoreEngine() {
        return storeEngine;
    }

    public boolean isLeader() {
        return this.node.isLeader();
    }

    public PeerId getLeaderId() {
        return this.node.getLeaderId();
    }

    public RaftRawKVStore getRaftRawKVStore() {
        return raftRawKVStore;
    }

    public MetricsRawKVStore getMetricsRawKVStore() {
        return metricsRawKVStore;
    }

    public Node getNode() {
        return node;
    }

    public KVStoreStateMachine getFsm() {
        return fsm;
    }

    public RegionEngineOptions copyRegionOpts() {
        final RegionEngineOptions copy = new RegionEngineOptions();
        copy.setRegionId(this.regionOpts.getRegionId());
        copy.setStartKey(this.regionOpts.getStartKey());
        copy.setStartKeyBytes(this.regionOpts.getStartKeyBytes());
        copy.setEndKey(this.regionOpts.getEndKey());
        copy.setEndKeyBytes(this.regionOpts.getEndKeyBytes());
        copy.setNodeOptions(JRaftHelper.copyNodeOptionsFrom(this.regionOpts.getNodeOptions()));
        copy.setRaftGroupId(this.regionOpts.getRaftGroupId());
        copy.setRaftDataPath(this.regionOpts.getRaftDataPath());
        copy.setServerAddress(this.regionOpts.getServerAddress());
        copy.setInitialServerList(this.regionOpts.getInitialServerList());
        copy.setMetricsReportPeriod(this.regionOpts.getMetricsReportPeriod());
        return copy;
    }

    @Override
    public String toString() {
        return "RegionEngine{" + "region=" + region + ", regionOpts=" + regionOpts + '}';
    }
}
