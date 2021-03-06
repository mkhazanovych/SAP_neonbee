package io.neonbee;

import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;

import com.google.common.annotations.VisibleForTesting;

import io.vertx.core.cli.CLI;
import io.vertx.core.cli.CLIException;
import io.vertx.core.cli.CommandLine;
import io.vertx.core.cli.Option;
import io.vertx.core.cli.TypedOption;

public class Launcher {
    private static final Option HELP_FLAG = new Option().setLongName("help").setShortName("h")
            .setDescription("Shows help").setRequired(false).setFlag(true).setHelp(true);

    private static final Option WORKING_DIR = new Option().setLongName("working-directory").setShortName("cwd")
            .setDescription("Sets the current working directory of the NeonBee instance").setRequired(false);

    private static final Option INSTANCE_NAME = new Option().setLongName("instance-name").setShortName("name")
            .setDescription("Sets the instance name for the NeonBee instance").setRequired(false);

    private static final TypedOption<Integer> WORKER_POOL_SIZE =
            new TypedOption<Integer>().setLongName("worker-pool-size").setShortName("wps")
                    .setDescription("Sets the number of threads for the worker pool used by the NeonBee instance")
                    .setRequired(false).setType(Integer.class);

    private static final TypedOption<Integer> EVENT_LOOP_POOL_SIZE =
            new TypedOption<Integer>().setLongName("event-loop-pool-size").setShortName("elps")
                    .setDescription("Sets the number of threads for the event loop pool used by the NeonBee instance")
                    .setRequired(false).setType(Integer.class);

    private static final Option IGNORE_CLASS_PATH_FLAG =
            new Option().setLongName("ignore-class-path").setShortName("no-cp")
                    .setDescription("Sets whether NeonBee should ignore verticle and models on the class path")
                    .setRequired(false).setFlag(true);

    private static final Option DISABLE_JOB_SCHEDULING_FLAG = new Option().setLongName("disable-job-scheduling")
            .setShortName("no-jobs").setDescription("Sets whether NeonBee should schedule any job verticle")
            .setRequired(false).setFlag(true);

    private static final Option DO_NOT_WATCH_FILES =
            new Option().setLongName("do-not-watch-files").setShortName("no-watchers")
                    .setDescription("Sets whether NeonBee should watch any files").setRequired(false).setFlag(true);

    private static final Option CLUSTERED = new Option().setLongName("clustered").setShortName("cl")
            .setDescription("Sets whether NeonBee should be started in clustered mode").setRequired(false)
            .setFlag(true);

    private static final TypedOption<Integer> CLUSTER_PORT = new TypedOption<Integer>().setLongName("cluster-port")
            .setShortName("clp").setDescription("Sets the port of cluster event bus of the clustered NeonBee instance")
            .setRequired(false).setType(Integer.class);

    private static final Option CLUSTER_CONFIG = new Option().setLongName("cluster-config").setShortName("cc")
            .setDescription("Sets the cluster/Hazelast configuration file for NeonBee").setRequired(false);

    private static final TypedOption<Integer> SERVER_PORT = new TypedOption<Integer>().setLongName("server-port")
            .setShortName("port").setDescription("Sets the HTTP port of server of the clustered NeonBee instance")
            .setRequired(false).setType(Integer.class);

    private static final Option ACTIVE_PROFILES = new Option().setLongName("active-profiles").setShortName("ap")
            .setDescription("Sets the active deployment profiles of NeonBee").setRequired(false);

    private static final List<Option> OPTIONS = List.of(WORKING_DIR, INSTANCE_NAME, WORKER_POOL_SIZE,
            EVENT_LOOP_POOL_SIZE, IGNORE_CLASS_PATH_FLAG, DISABLE_JOB_SCHEDULING_FLAG, DO_NOT_WATCH_FILES, CLUSTERED,
            CLUSTER_PORT, CLUSTER_CONFIG, SERVER_PORT, ACTIVE_PROFILES, HELP_FLAG);

    @VisibleForTesting
    static final CLI INTERFACE = CLI.create("neonbee").setSummary("Start a NeonBee instance").addOptions(OPTIONS);

    @SuppressWarnings("unused")
    private static NeonBee neonBee;

    @SuppressWarnings("checkstyle:MissingJavadocMethod")
    public static void main(String[] args) {
        CommandLine commandLine = null;
        try {
            commandLine = INTERFACE.parse(List.of(args), true);
            List<String> arguments = commandLine.allArguments();
            if (!arguments.isEmpty()) {
                throw new CLIException("Unknown option '" + arguments.get(0) + "'");
            }
        } catch (CLIException e) {
            System.err.print(e.getMessage() + ".\n\nUse --help to list all options."); // NOPMD
            System.exit(1); // NOPMD
        }

        if (commandLine.isAskingForHelp()) {
            StringBuilder builder = new StringBuilder();
            INTERFACE.usage(builder);
            System.out.print(builder.toString()); // NOPMD
            System.exit(0); // NOPMD
        }

        try {
            NeonBeeOptions options = setOptions(commandLine);

            ServiceLoader<LauncherPreProcessor> loader = ServiceLoader.load(LauncherPreProcessor.class);
            loader.forEach(processor -> processor.execute(options));

            NeonBee.create(options).onSuccess(neonBee -> {
                Launcher.neonBee = neonBee;
            }).onFailure(throwable -> {
                System.err.println("Failed to start NeonBee '" + throwable.getMessage() + "'"); // NOPMD
            });
        } catch (Exception e) {
            System.err.println("Error occurred during launcher pre-processing. " + e.getMessage()); // NOPMD
            System.exit(1); // NOPMD
        }
    }

    @VisibleForTesting
    static NeonBeeOptions setOptions(CommandLine commandLine) {
        NeonBeeOptions.Mutable neonBeeOptions = new NeonBeeOptions.Mutable();

        getLauncherOptionStringValue(commandLine, WORKING_DIR).or(() -> Optional.of("./working_dir/"))
                .ifPresent(cwd -> neonBeeOptions.setWorkingDirectory(Paths.get(cwd)));
        getLauncherOptionStringValue(commandLine, INSTANCE_NAME).ifPresent(neonBeeOptions::setInstanceName);
        getLauncherOptionIntegerValue(commandLine, EVENT_LOOP_POOL_SIZE)
                .ifPresent(neonBeeOptions::setEventLoopPoolSize);
        getLauncherOptionIntegerValue(commandLine, WORKER_POOL_SIZE).ifPresent(neonBeeOptions::setWorkerPoolSize);
        getLauncherOptionIntegerValue(commandLine, CLUSTER_PORT).ifPresent(neonBeeOptions::setClusterPort);
        getLauncherOptionStringValue(commandLine, CLUSTER_CONFIG).ifPresent(neonBeeOptions::setClusterConfigResource);
        getLauncherOptionIntegerValue(commandLine, SERVER_PORT).ifPresent(neonBeeOptions::setServerPort);
        getLauncherOptionStringValue(commandLine, ACTIVE_PROFILES).ifPresent(neonBeeOptions::setActiveProfileValues);

        neonBeeOptions.setIgnoreClassPath(getLauncherOptionBooleanValue(commandLine, IGNORE_CLASS_PATH_FLAG));
        neonBeeOptions.setDisableJobScheduling(getLauncherOptionBooleanValue(commandLine, DISABLE_JOB_SCHEDULING_FLAG));
        neonBeeOptions.setDoNotWatchFiles(getLauncherOptionBooleanValue(commandLine, DO_NOT_WATCH_FILES));
        neonBeeOptions.setClustered(getLauncherOptionBooleanValue(commandLine, CLUSTERED));

        return neonBeeOptions;
    }

    @VisibleForTesting
    static boolean getLauncherOptionBooleanValue(CommandLine commandLine, Option option) {
        if (commandLine.isFlagEnabled(option.getName())) {
            return true;
        }
        return Optional.ofNullable(System.getenv(transformToEnvName(option.getLongName()))).map(Boolean::parseBoolean)
                .orElse(false);
    }

    @VisibleForTesting
    static Optional<Integer> getLauncherOptionIntegerValue(CommandLine commandLine, Option option) {
        return Optional.ofNullable(commandLine.<Integer>getOptionValue(option.getName())).or(() -> Optional
                .ofNullable(System.getenv(transformToEnvName(option.getLongName()))).map(Integer::parseInt));
    }

    @VisibleForTesting
    static Optional<String> getLauncherOptionStringValue(CommandLine commandLine, Option option) {
        return Optional.ofNullable(commandLine.<String>getOptionValue(option.getName()))
                .or(() -> Optional.ofNullable(System.getenv(transformToEnvName(option.getLongName()))));
    }

    private static String transformToEnvName(String longName) {
        return "NEONBEE_" + longName.replaceAll("-", "_").toUpperCase(Locale.ROOT);
    }
}
