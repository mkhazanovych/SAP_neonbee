package io.neonbee;

import static io.neonbee.NeonBeeProfile.ALL;
import static io.neonbee.internal.helper.StringHelper.EMPTY;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;

import io.neonbee.internal.verticle.WatchVerticle;
import io.neonbee.job.JobVerticle;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.EventBusOptions;

public interface NeonBeeOptions {
    /**
     * Get the maximum number of worker threads to be used by the NeonBee instance.
     * <p>
     * Worker threads are used for running blocking code and worker verticle.
     *
     * @return the maximum number of worker threads
     */
    int getEventLoopPoolSize();

    /**
     * Get the maximum number of worker threads to be used by the NeonBee instance.
     * <p>
     * Worker threads are used for running blocking code and worker verticle.
     *
     * @return the maximum number of worker threads
     */
    int getWorkerPoolSize();

    /**
     * Returns the name of the NeonBee instance.
     *
     * @return the name of the NeonBee instance
     */
    String getInstanceName();

    /**
     * Returns the current working directory path.
     *
     * @return the current working directory path
     */
    Path getWorkingDirectory();

    /**
     * Returns the config directory path resolved against the {@link #getWorkingDirectory working directory}.
     *
     * @return the config directory path
     */
    default Path getConfigDirectory() {
        return getWorkingDirectory().resolve("config");
    }

    /**
     * Returns the verticle directory path resolved against the {@link #getWorkingDirectory working directory}.
     *
     * @return the verticle directory path
     */
    default Path getVerticlesDirectory() {
        return getWorkingDirectory().resolve("verticles");
    }

    /**
     * Returns the models directory path resolved against the {@link #getWorkingDirectory working directory}.
     *
     * @return the models directory path
     */
    default Path getModelsDirectory() {
        return getWorkingDirectory().resolve("models");
    }

    /**
     * Returns the logs directory path resolved against the {@link #getWorkingDirectory working directory}.
     *
     * @return the logs directory path
     */
    default Path getLogDirectory() {
        return getWorkingDirectory().resolve("logs");
    }

    /**
     * Check if NeonBee should ignore verticle / models on the class path.
     *
     * @return true if class path should be ignored, otherwise false.
     */
    boolean shouldIgnoreClassPath();

    /**
     * Check if NeonBee should disable scheduling jobs via {@link JobVerticle}s.
     *
     * @return true if NeonBee should not schedule any job verticle, otherwise false.
     */
    boolean shouldDisableJobScheduling();

    /**
     * Check if NeonBee should disable watching files via {@link WatchVerticle}s.
     *
     * @return true if NeonBee should not watch files, otherwise false.
     */
    boolean doNotWatchFiles();

    /**
     * Get the port number of the event bus. If not set, a random port will be selected.
     *
     * @return the port number of the event bus
     */
    int getClusterPort();

    /**
     * Whether NeonBee should be started in cluster mode.
     *
     * @return whether NeonBee should be started in cluster mode
     */
    boolean isClustered();

    /**
     * Gets Hazelcast cluster configuration.
     *
     * @return Hazelcast cluster configuration
     */
    Config getClusterConfig();

    /**
     * Get the port number of the server verticle. If not set, the port number will be retrieved from the server
     * verticle config.
     *
     * @return the port number of the server verticle
     */
    Integer getServerPort();

    /**
     * Gets the currently active profiles.
     *
     * @return the currently active profiles.
     */
    Set<NeonBeeProfile> getActiveProfiles();

    /**
     * Create a mutable NeonBeeOptions similar to VertxOptions, but as NeonBeeOptions are exposed only the interface
     * shall be used, otherwise configuration changes could cause runtime errors. To initialize a new Vertx instance use
     * this Mutable inner class.
     */
    class Mutable implements NeonBeeOptions {
        /**
         * The default cluster configuration file name.
         */
        public static final String DEFAULT_CLUSTER_CONFIG = "hazelcast-cf.xml";

        private int eventLoopPoolSize = VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE;

        private int workerPoolSize = VertxOptions.DEFAULT_WORKER_POOL_SIZE;

        private int clusterPort = EventBusOptions.DEFAULT_CLUSTER_PORT;

        private boolean clustered;

        private Config clusterConfig;

        private String instanceName;

        private Path workingDirectoryPath = Path.of(EMPTY);

        private boolean ignoreClassPath;

        private boolean disableJobScheduling;

        private boolean doNotWatchFiles;

        private Integer serverPort;

        private Set<NeonBeeProfile> activeProfiles = Set.of(ALL);

        /**
         * Instantiates a mutable {@link NeonBeeOptions} instance.
         */
        public Mutable() {
            instanceName = generateName();
        }

        @Override
        public int getEventLoopPoolSize() {
            return eventLoopPoolSize;
        }

        /**
         * Set the maximum number of event loop threads to be used by the NeonBee instance. The number of threads must
         * be larger then 0.
         *
         * @param eventLoopPoolSize the number of threads
         * @return a reference to this, so the API can be used fluently
         */
        public Mutable setEventLoopPoolSize(int eventLoopPoolSize) {
            if (eventLoopPoolSize < 1) {
                throw new IllegalArgumentException("eventLoopSize must be > 0");
            }
            this.eventLoopPoolSize = eventLoopPoolSize;
            return this;
        }

        @Override
        public int getWorkerPoolSize() {
            return workerPoolSize;
        }

        /**
         * Set the maximum number of worker threads to be used by the NeonBee instance. The number of threads must be
         * larger then 0.
         *
         * @param workerPoolSize the number of threads
         * @return a reference to this, so the API can be used fluently
         */
        public Mutable setWorkerPoolSize(int workerPoolSize) {
            if (workerPoolSize < 1) {
                throw new IllegalArgumentException("workerPoolSize must be > 0");
            }
            this.workerPoolSize = workerPoolSize;
            return this;
        }

        @Override
        public String getInstanceName() {
            return instanceName;
        }

        /**
         * Set the name of the NeonBee instance. The instance name must have at least one character. If null is passed,
         * a new instance name will be generated.
         *
         * @param instanceName the name of the NeonBee instance
         * @return a reference to this, so the API can be used fluently
         */
        public Mutable setInstanceName(String instanceName) {
            if (Objects.isNull(instanceName)) {
                this.instanceName = generateName();
            } else if (instanceName.isEmpty()) {
                throw new IllegalArgumentException("instanceName must not be empty");
            } else {
                this.instanceName = instanceName;
            }
            return this;
        }

        @Override
        public Path getWorkingDirectory() {
            return workingDirectoryPath;
        }

        /**
         * Set the working directory of the NeonBee instance. The working directory must be not null and must exist on
         * the file system.
         *
         * @param workingDirectory the name of the NeonBee instance
         * @return a reference to this, so the API can be used fluently
         */
        public Mutable setWorkingDirectory(Path workingDirectory) {
            requireNonNull(workingDirectory, "workingDirectory must not be null");
            this.workingDirectoryPath = workingDirectory.toAbsolutePath().normalize();
            return this;
        }

        @Override
        public boolean shouldIgnoreClassPath() {
            return ignoreClassPath;
        }

        /**
         * Sets whether NeonBee should ignore verticle / models on the class path.
         *
         * @param ignoreClassPath flag true/false
         * @return a reference to this, so the API can be used fluently
         */
        public Mutable setIgnoreClassPath(boolean ignoreClassPath) {
            this.ignoreClassPath = ignoreClassPath;
            return this;
        }

        @Override
        public boolean shouldDisableJobScheduling() {
            return disableJobScheduling;
        }

        /**
         * Sets whether NeonBee should not schedule any job verticle.
         *
         * @param disableJobScheduling flag true/false
         * @return a reference to this, so the API can be used fluently
         */
        public Mutable setDisableJobScheduling(boolean disableJobScheduling) {
            this.disableJobScheduling = disableJobScheduling;
            return this;
        }

        @Override
        public boolean doNotWatchFiles() {
            return doNotWatchFiles;
        }

        /**
         * Sets whether NeonBee should watch files or not.
         *
         * @param doNotWatchFiles flag true/false
         * @return a reference to this, so the API can be used fluently
         */
        public Mutable setDoNotWatchFiles(boolean doNotWatchFiles) {
            this.doNotWatchFiles = doNotWatchFiles;
            return this;
        }

        @Override
        public int getClusterPort() {
            return clusterPort;
        }

        @Override
        public boolean isClustered() {
            return clustered;
        }

        @Override
        public Config getClusterConfig() {
            if (clusterConfig == null) {
                setClusterConfigResource(DEFAULT_CLUSTER_CONFIG);
            }
            return clusterConfig;
        }

        /**
         * Set the cluster config file.
         *
         * @param clusterConfigFile the cluster config file
         * @return this instance for chaining
         */
        public Mutable setClusterConfigResource(String clusterConfigFile) {
            this.clusterConfig = new ClasspathXmlConfig(clusterConfigFile);
            return this;
        }

        /**
         * Set the cluster config.
         *
         * @param config the cluster config
         * @return this instance for chaining
         */
        public Mutable setClusterConfig(Config config) {
            this.clusterConfig = config;
            return this;
        }

        /**
         * Set the cluster port.
         *
         * @param clusterPort the cluster port
         * @return this instance for chaining
         */
        public Mutable setClusterPort(int clusterPort) {
            this.clusterPort = clusterPort;
            return this;
        }

        /**
         * Set clustered.
         *
         * @param clustered true if clustered
         * @return this instance for chaining
         */
        public Mutable setClustered(boolean clustered) {
            this.clustered = clustered;
            return this;
        }

        /**
         * Set the server port.
         *
         * @param serverPort the server port
         * @return this instance for chaining
         */
        public Mutable setServerPort(Integer serverPort) {
            this.serverPort = serverPort;
            return this;
        }

        @Override
        public Integer getServerPort() {
            return this.serverPort;
        }

        @Override
        public Set<NeonBeeProfile> getActiveProfiles() {
            return this.activeProfiles;
        }

        /**
         * Set the active profiles.
         *
         * @param profiles the active profiles
         * @return this instance for chaining
         */
        public Mutable setActiveProfiles(Collection<NeonBeeProfile> profiles) {
            this.activeProfiles = ImmutableSet.copyOf(requireNonNull(profiles));
            return this;
        }

        /**
         * Add an active profile.
         *
         * @param profile the active profile to add
         * @return this instance for chaining
         */
        public Mutable addActiveProfile(NeonBeeProfile profile) {
            this.activeProfiles = Sets.union(this.activeProfiles, Set.of(profile)).immutableCopy();
            return this;
        }

        /**
         * Remove an active profile.
         *
         * @param profile the active profile to remove
         * @return this instance for chaining
         */
        public Mutable removeActiveProfile(NeonBeeProfile profile) {
            this.activeProfiles = Sets.difference(this.activeProfiles, Set.of(profile)).immutableCopy();
            return this;
        }

        /**
         * Remove all active profiles. Equivalent of setting an empty set.
         *
         * @return this instance for chaining
         */
        public Mutable clearActiveProfiles() {
            this.activeProfiles = Set.of();
            return this;
        }

        /**
         * Set the active profile values.
         *
         * @param values the profile values
         * @return this instance for chaining
         */
        public Mutable setActiveProfileValues(String values) {
            return this.setActiveProfiles(NeonBeeProfile.parseProfiles(values));
        }

        private String generateName() {
            return String.format("%s-%s", NeonBee.class.getSimpleName(), UUID.randomUUID().toString());
        }
    }
}
