package org.rhq.modules.plugins.jbossas7.helper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.domain.configuration.Configuration;
import org.rhq.core.pluginapi.component.ComponentInvocationContext;
import org.rhq.core.pluginapi.util.ProcessExecutionUtility;
import org.rhq.core.pluginapi.util.StartScriptConfiguration;
import org.rhq.core.system.ProcessExecution;
import org.rhq.core.system.ProcessExecutionResults;
import org.rhq.core.system.SystemInfo;
import org.rhq.modules.plugins.jbossas7.AS7Mode;
import org.rhq.modules.plugins.jbossas7.BaseServerComponent;

/**
 * @author Lukas Krejci
 * @since 4.12
 */
public final class ServerCommandRunner {

    private static final Log LOG = LogFactory.getLog(ServerCommandRunner.class);

    private static final long MAX_PROCESS_WAIT_TIME = 3600000L;

    private final ServerPluginConfiguration serverPluginConfig;
    private final Configuration pluginConfiguration;
    private final Map<String, String> startScriptEnv;
    private final AS7Mode serverMode;
    private final SystemInfo systemInfo;

    private int waitTime;
    private boolean killOnTimeout;

    private ServerCommandRunner(Configuration pluginConfiguration, AS7Mode serverMode, SystemInfo systemInfo) {
        this.serverPluginConfig = new ServerPluginConfiguration(pluginConfiguration);
        this.pluginConfiguration = pluginConfiguration;
        this.serverMode = serverMode;
        this.systemInfo = systemInfo;
        this.startScriptEnv = new StartScriptConfiguration(pluginConfiguration).getStartScriptEnv();

        for (String envVarName : startScriptEnv.keySet()) {
            String envVarValue = startScriptEnv.get(envVarName);
            envVarValue = BaseServerComponent.replacePropertyPatterns(envVarValue, pluginConfiguration);
            startScriptEnv.put(envVarName, envVarValue);
        }
    }

    public static ServerCommandRunner onServer(Configuration serverPluginConfig, AS7Mode serverMode,
        SystemInfo systemInfo) {

        return new ServerCommandRunner(serverPluginConfig, serverMode, systemInfo);
    }

    /**
     * 0, the default, means waiting forever. Any positive number means waiting for given number of seconds
     * and timing out afterwards. Any negative value means timing out immediately.
     */
    public ServerCommandRunner waitingFor(int seconds) {
        this.waitTime = seconds;
        return this;
    }

    public ServerCommandRunner killingOnTimeout(boolean kill) {
        killOnTimeout = kill;
        return this;
    }

    public ProcessExecutionResults runCliCommand(String command) {
        return run(new File("bin", serverMode.getCliScriptFileName()),
            "-c",
            "--commands=" + command,
            "--user=" + serverPluginConfig.getUser(),
            "--password=" + serverPluginConfig.getPassword(),
            "--controller=" + serverPluginConfig.getNativeHost() + ":" + serverPluginConfig.getNativePort());
    }

    public ProcessExecutionResults runCliScript(File scriptFile) {
        File homeDir = serverPluginConfig.getHomeDir();

        File script = scriptFile;
        if (!script.isAbsolute()) {
            script = new File(homeDir, scriptFile.getPath());
        }

        return run(new File("bin", serverMode.getCliScriptFileName()),
            "-c",
            "--file=" + script.getAbsolutePath(),
            "--user=" + serverPluginConfig.getUser(),
            "--password=" + serverPluginConfig.getPassword(),
            "--controller=" + serverPluginConfig.getNativeHost() + ":" + serverPluginConfig.getNativePort());
    }

    /**
     * This command ignores the timeout set by the {@link #waitingFor(int)} method. It starts the process and returns
     * immediately. Other means have to be used to determine if the server finished starting up.
     */
    public ProcessExecutionResults startServer() {
        StartScriptConfiguration startScriptConfiguration = new StartScriptConfiguration(pluginConfiguration);
        File startScriptFile = startScriptConfiguration.getStartScript();

        if (startScriptFile == null) {
            startScriptFile = new File("bin", serverMode.getStartScriptFileName());
        }

        List<String> startScriptArgsL = startScriptConfiguration.getStartScriptArgs();
        String[] startScriptArgs = startScriptArgsL.toArray(new String[startScriptArgsL.size()]);

        for (int i = 0; i < startScriptArgs.length; ++i) {
            startScriptArgs[i] = BaseServerComponent.replacePropertyPatterns(startScriptArgs[i], pluginConfiguration);
        }

        int origWaitTime = waitTime;
        try {
            //we really don't want to wait for the server start, because, hopefully, it will keep on running ;)
            waitTime = -1;
            return run(startScriptFile, startScriptArgs);
        } finally {
            waitTime = origWaitTime;
        }
    }

    public ProcessExecutionResults shutdownServer() {
        return runCliCommand("shutdown");
    }

    private ProcessExecutionResults run(File executable, String... args) {
        File homeDir = serverPluginConfig.getHomeDir();
        File startScriptFile = executable.isAbsolute() ? executable : new File(homeDir, executable.getPath());

        ProcessExecution processExecution = ProcessExecutionUtility.createProcessExecution(null,
            startScriptFile);

        processExecution.setEnvironmentVariables(startScriptEnv);

        List<String> arguments = processExecution.getArguments();
        if (arguments == null) {
            arguments = new ArrayList<String>();
            processExecution.setArguments(arguments);
        }

        for(String arg : args) {
            arguments.add(arg);
        }

        // When running on Windows 9x, standalone.bat and domain.bat need the cwd to be the AS bin dir in order to find
        // standalone.bat.conf and domain.bat.conf respectively.
        processExecution.setWorkingDirectory(startScriptFile.getParent());
        processExecution.setCaptureOutput(true);
        processExecution.setWaitForCompletion(MAX_PROCESS_WAIT_TIME);
        processExecution.setKillOnTimeout(false);

        if (waitTime > 0) {
            processExecution.setWaitForCompletion(waitTime * 1000);
        } else if (waitTime < 0) {
            processExecution.setWaitForCompletion(0);
        }

        processExecution.setKillOnTimeout(killOnTimeout);

        if (LOG.isDebugEnabled()) {
            LOG.debug("About to execute the following process: [" + processExecution + "]");
        }

        return systemInfo.executeProcess(processExecution);
    }
}
