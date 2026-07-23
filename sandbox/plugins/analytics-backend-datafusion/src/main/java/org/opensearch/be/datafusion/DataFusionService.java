/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.be.datafusion;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.analytics.AnalyticsSettings;
import org.opensearch.be.datafusion.cache.CacheManager;
import org.opensearch.be.datafusion.cache.CacheUtils;
import org.opensearch.be.datafusion.cache.NativeCacheManagerHandle;
import org.opensearch.be.datafusion.nativelib.NativeBridge;
import org.opensearch.be.datafusion.stats.DataFusionStats;
import org.opensearch.be.datafusion.stats.SpillStats;
import org.opensearch.be.datafusion.stats.SpillStatsCollector;
import org.opensearch.common.lifecycle.AbstractLifecycleComponent;
import org.opensearch.common.settings.ClusterSettings;

import java.io.IOException;
import java.util.Collection;

/**
 * Node-level service managing the DataFusion native runtime lifecycle.
 * <p>
 * Initializes the Tokio runtime manager (dedicated CPU + IO thread pools)
 * and the DataFusion global runtime (memory pool, disk spill, cache).
 * All per-query {@link DatafusionSearchExecEngine} instances share these resources.
 * <p>
 * Use {@link #builder()} to construct.
 */
public class DataFusionService extends AbstractLifecycleComponent {

    private static final Logger logger = LogManager.getLogger(DataFusionService.class);

    private final long memoryPoolLimit;
    /**
     * Mirrors the dynamic {@code datafusion.spill_memory_limit_bytes} setting and the
     * native runtime's spill cap. Updated by {@link #setSpillMemoryLimit(long)}; surfaced
     * as {@code spill.disk_reserved_bytes} via {@link #buildSpillStats()}. Volatile so
     * stats readers see updates from the cluster-settings consumer thread without locking.
     */
    private volatile long spillMemoryLimit;
    private final String spillDirectory;
    private final int cpuThreads;
    private final double datanodeMultiplier;
    private final double coordinatorMultiplier;
    private final ClusterSettings clusterSettings;

    /** Handle to the native DataFusion global runtime (memory pool + cache). */
    private volatile NativeRuntimeHandle runtimeHandle;

    /**
     * Actual bound port of this node's Rust-native shuffle Flight server, or {@code -1} if the
     * transport is disabled ({@link AnalyticsSettings#MPP_SHUFFLE_FLIGHT_ENABLED}). Set in
     * {@link #doStart()}; published for producers to target.
     */
    private volatile int flightShufflePort = -1;

    /** Cache manager for pre-warming and managing native caches. */
    private volatile CacheManager cacheManager;

    private DataFusionService(Builder builder) {
        this.memoryPoolLimit = builder.memoryPoolLimit;
        this.spillMemoryLimit = builder.spillMemoryLimit;
        this.spillDirectory = builder.spillDirectory;
        this.cpuThreads = builder.cpuThreads;
        this.datanodeMultiplier = builder.datanodeMultiplier;
        this.coordinatorMultiplier = builder.coordinatorMultiplier;
        this.clusterSettings = builder.clusterSettings;
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the number of available CPU threads used for the dedicated executor.
     * This is the value used to compute concurrency gate permit counts (cpu_threads × multiplier).
     */
    public static int cpuThreadCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    @Override
    protected void doStart() {
        logger.debug("Starting DataFusion service");
        NativeBridge.initTokioRuntimeManager(cpuThreads, datanodeMultiplier, coordinatorMultiplier);
        logger.debug(
            "Tokio runtime manager initialized with {} CPU threads, datanode multiplier {}, coordinator multiplier {}",
            cpuThreads,
            datanodeMultiplier,
            coordinatorMultiplier
        );

        long cacheManagerPtr = 0L;
        NativeCacheManagerHandle cacheHandle = null;
        if (clusterSettings != null) {
            cacheHandle = CacheUtils.createCacheConfig(clusterSettings);
            cacheManagerPtr = cacheHandle.getPointer();
        }

        try {
            long ptr = NativeBridge.createGlobalRuntime(memoryPoolLimit, cacheManagerPtr, spillDirectory, spillMemoryLimit);
            if (cacheHandle != null) {
                cacheHandle.markConsumed();
            }
            this.runtimeHandle = new NativeRuntimeHandle(ptr);
        } catch (Exception e) {
            if (cacheHandle != null) {
                cacheHandle.close();
            }
            throw e;
        }

        if (clusterSettings != null) {
            this.cacheManager = new CacheManager(runtimeHandle);
        }

        // Rust-native shuffle Flight server: bind on the native IO runtime if enabled. The bound port
        // is published for producers to target. Non-dynamic — bound once here. Failure to bind is not
        // fatal: log and leave the port at -1 so the query layer stays on the Java shuffle path.
        if (clusterSettings != null && clusterSettings.get(AnalyticsSettings.MPP_SHUFFLE_FLIGHT_ENABLED)) {
            int requestedPort = clusterSettings.get(AnalyticsSettings.MPP_SHUFFLE_FLIGHT_PORT);
            try {
                this.flightShufflePort = NativeBridge.startFlightShuffleServer(requestedPort);
                logger.info("Rust-native shuffle Flight server bound on port {}", flightShufflePort);
            } catch (Exception e) {
                logger.warn("Failed to start Rust-native shuffle Flight server; staying on Java shuffle path", e);
                this.flightShufflePort = -1;
            }
        }

        logger.debug("DataFusion service started — memory pool {}B, spill limit {}B", memoryPoolLimit, spillMemoryLimit);
    }

    /**
     * Actual bound port of this node's Rust-native shuffle Flight server, or {@code -1} if the
     * transport is disabled or failed to bind. Published for producers to target.
     */
    public int flightShufflePort() {
        return flightShufflePort;
    }

    @Override
    protected void doStop() {
        logger.debug("Stopping DataFusion service");
        try {
            if (flightShufflePort >= 0) {
                NativeBridge.stopFlightShuffleServer();
                flightShufflePort = -1;
            }
            releaseRuntime();
        } finally {
            NativeBridge.shutdownTokioRuntimeManager();
        }

        logger.debug("DataFusion service stopped");
    }

    @Override
    protected void doClose() throws IOException {
        if (flightShufflePort >= 0) {
            NativeBridge.stopFlightShuffleServer();
            flightShufflePort = -1;
        }
        releaseRuntime();
        NativeBridge.shutdownTokioRuntimeManager();
    }

    /**
     * Returns the handle to the native DataFusion global runtime.
     *
     * @throws IllegalStateException if the service has not been started
     */
    public NativeRuntimeHandle getNativeRuntime() {
        NativeRuntimeHandle handle = runtimeHandle;
        if (handle == null) {
            throw new IllegalStateException("DataFusionService has not been started");
        }
        return handle;
    }

    /**
     * Returns the current memory pool usage in bytes.
     */
    public long getMemoryPoolUsage() {
        return NativeBridge.getMemoryPoolUsage(getNativeRuntime().get());
    }

    /**
     * Returns the current memory pool limit in bytes.
     */
    public long getMemoryPoolLimit() {
        return NativeBridge.getMemoryPoolLimit(getNativeRuntime().get());
    }

    /** Returns [usage_bytes, tripped_count] from the native memory pool. Single FFM call. */
    public long[] getMemoryPoolStats() {
        return NativeBridge.getMemoryPoolStats(getNativeRuntime().get());
    }

    /**
     * Sets the memory pool limit at runtime. Takes effect for new allocations only.
     * Existing reservations that exceed the new limit are NOT reclaimed.
     * <p>
     * The user-visible info-level log line is emitted by the caller in
     * {@code DataFusionPlugin.updateMemoryPoolLimit}; this method is silent to avoid
     * duplicate log entries.
     */
    public void setMemoryPoolLimit(long newLimitBytes) {
        NativeBridge.setMemoryPoolLimit(getNativeRuntime().get(), newLimitBytes);
    }

    /**
     * Returns true if the loaded native library can update the spill cap at runtime.
     * When false, {@link #setSpillMemoryLimit(long)} will throw and cluster-state
     * updates of {@code datafusion.spill_memory_limit_bytes} have no live effect.
     */
    public boolean isSpillLimitDynamic() {
        return NativeBridge.isSpillLimitDynamic();
    }

    /**
     * Sets the spill memory limit at runtime. Requires {@link #isSpillLimitDynamic()};
     * otherwise throws {@link UnsupportedOperationException}. Also updates the Java-side
     * mirror used by {@link #buildSpillStats()} so {@code spill.disk_reserved_bytes}
     * reflects the new value on the next stats read.
     */
    public void setSpillMemoryLimit(long newLimitBytes) {
        NativeBridge.setSpillLimit(getNativeRuntime().get(), newLimitBytes);
        this.spillMemoryLimit = newLimitBytes;
    }

    /**
     * Returns the spill directory this service was configured with. Empty string
     * means spill is disabled (DataFusion built with {@code DiskManagerMode::Disabled}).
     */
    public String getSpillDirectory() {
        return spillDirectory;
    }

    /**
     * Returns the spill memory limit (in bytes) this service was configured with.
     * Surfaced for the {@code spill.disk_reserved_bytes} stat.
     */
    public long getSpillMemoryLimit() {
        return spillMemoryLimit;
    }

    /**
     * Returns the latest native executor stats, collected fresh from JNI on every call.
     *
     * @return the current {@link DataFusionStats}
     */
    public DataFusionStats getStats() {
        if (runtimeHandle == null) {
            throw new IllegalStateException("DataFusionService has not been started");
        }
        DataFusionStats nativeStats = NativeBridge.stats(runtimeHandle.get());
        SpillStats spill = buildSpillStats();
        return new DataFusionStats(
            nativeStats.getNativeExecutorsStats(),
            nativeStats.getFragmentExecutorGateStats(),
            nativeStats.getAdaptiveBudgetStats(),
            spill,
            nativeStats.getCacheStats(),
            nativeStats.getSearchStats()
        );
    }

    /**
     * Builds a {@link SpillStats} snapshot from the configured spill directory and the
     * configured spill memory limit. Package-private so unit tests can verify the wiring
     * without starting the native runtime.
     */
    SpillStats buildSpillStats() {
        return SpillStatsCollector.collect(spillDirectory, spillMemoryLimit);
    }
    // Cache management (node-level, delegates to native runtime)

    /**
     * Returns the cache manager, or null if caching is not configured.
     */
    public CacheManager getCacheManager() {
        return cacheManager;
    }

    /**
     * Notifies the native cache that new files are available for caching.
     * @param filePaths absolute paths of the new files
     */
    public void onFilesAdded(Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return;
        try {
            NativeBridge.cacheManagerAddFiles(runtimeHandle.get(), filePaths.toArray(new String[0]));
        } catch (Exception e) {
            logger.warn("Failed to register new files with native cache", e);
        }
    }

    /**
     * Warmup files through the TieredObjectStore — reads metadata via the store
     * (populating Foyer caches) and puts into heap cache. For warm nodes where
     * files are on S3, this routes through the registry to remote storage.
     *
     * @param filePaths absolute paths of the files to warm
     * @param storeBoxPtr Box&lt;Arc&lt;dyn ObjectStore&gt;&gt; pointer from TieredStorageBridge.getObjectStoreBoxPtr
     */
    public void onFilesAddedWithStore(Collection<String> filePaths, long storeBoxPtr) {
        if (filePaths == null || filePaths.isEmpty()) return;
        try {
            NativeBridge.cacheManagerAddFilesWithStore(runtimeHandle.get(), storeBoxPtr, filePaths.toArray(new String[0]));
        } catch (Exception e) {
            logger.warn("Failed to warmup files with store", e);
        }
    }

    /**
     * Notifies the native cache that files have been deleted and should be evicted.
     * @param filePaths absolute paths of the deleted files
     */
    public void onFilesDeleted(Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return;
        try {
            NativeBridge.cacheManagerRemoveFiles(runtimeHandle.get(), filePaths.toArray(new String[0]));
        } catch (Exception e) {
            logger.warn("Failed to evict deleted files from native cache", e);
        }
    }

    private void releaseRuntime() {
        NativeRuntimeHandle handle = runtimeHandle;
        if (handle != null) {
            handle.close();
            runtimeHandle = null;
            logger.debug("DataFusion native runtime released");
        }
    }

    /**
     * Builder for {@link DataFusionService}.
     */
    public static class Builder {
        private long memoryPoolLimit = Runtime.getRuntime().maxMemory() / 4;
        private long spillMemoryLimit = Runtime.getRuntime().maxMemory() / 8;
        private String spillDirectory = System.getProperty("java.io.tmpdir");
        private int cpuThreads = Runtime.getRuntime().availableProcessors();
        private double datanodeMultiplier = 1.0;
        private double coordinatorMultiplier = 1.0;
        private ClusterSettings clusterSettings;

        private Builder() {}

        /**
         * Sets the maximum bytes for the DataFusion memory pool.
         * @param bytes memory limit
         */
        public Builder memoryPoolLimit(long bytes) {
            this.memoryPoolLimit = bytes;
            return this;
        }

        /**
         * Sets the maximum bytes before spilling to disk.
         * @param bytes spill limit
         */
        public Builder spillMemoryLimit(long bytes) {
            this.spillMemoryLimit = bytes;
            return this;
        }

        /**
         * Sets the directory for spill files.
         * @param path spill directory
         */
        public Builder spillDirectory(String path) {
            this.spillDirectory = path;
            return this;
        }

        /**
         * Sets the number of CPU threads for the dedicated executor.
         * @param threads CPU thread count
         */
        public Builder cpuThreads(int threads) {
            this.cpuThreads = threads;
            return this;
        }

        /** Sets the datanode concurrency gate multiplier. */
        public Builder datanodeMultiplier(double multiplier) {
            this.datanodeMultiplier = multiplier;
            return this;
        }

        /** Sets the coordinator concurrency gate multiplier. */
        public Builder coordinatorMultiplier(double multiplier) {
            this.coordinatorMultiplier = multiplier;
            return this;
        }

        /**
         * Sets the cluster settings for cache configuration.
         * @param clusterSettings the cluster settings
         */
        public Builder clusterSettings(ClusterSettings clusterSettings) {
            this.clusterSettings = clusterSettings;
            return this;
        }

        /** Builds the {@link DataFusionService}. */
        public DataFusionService build() {
            return new DataFusionService(this);
        }
    }
}
