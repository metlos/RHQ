package org.rhq.modules.plugins.jbossas7;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.domain.bundle.BundleResourceDeployment;
import org.rhq.core.domain.bundle.BundleResourceDeploymentHistory;
import org.rhq.core.domain.configuration.PropertySimple;
import org.rhq.core.domain.measurement.AvailabilityType;
import org.rhq.core.pluginapi.bundle.BundleDeployRequest;
import org.rhq.core.pluginapi.bundle.BundleDeployResult;
import org.rhq.core.pluginapi.bundle.BundleFacet;
import org.rhq.core.pluginapi.bundle.BundleManagerProvider;
import org.rhq.core.pluginapi.bundle.BundlePurgeRequest;
import org.rhq.core.pluginapi.bundle.BundlePurgeResult;
import org.rhq.core.pluginapi.inventory.ResourceComponent;
import org.rhq.core.pluginapi.inventory.ResourceContext;
import org.rhq.core.system.ProcessExecutionResults;
import org.rhq.core.util.StringUtil;
import org.rhq.modules.plugins.jbossas7.helper.ServerCommandRunner;
import org.rhq.modules.plugins.jbossas7.helper.ServerPluginConfiguration;
import org.rhq.modules.plugins.jbossas7.json.Address;
import org.rhq.modules.plugins.jbossas7.json.Operation;
import org.rhq.modules.plugins.jbossas7.json.ReadAttribute;
import org.rhq.modules.plugins.jbossas7.json.Result;

/**
 * @author Lukas Krejci
 * @since 4.12
 */
public class PatchHandlerComponent implements ResourceComponent<ResourceComponent<?>>, BundleFacet {
    private static final Log LOG = LogFactory.getLog(PatchHandlerComponent.class);

    private static final String PATCH_ROLLBACK_COMMAND = "patch rollback --reset-configuration=false --patch-id=";

    private ResourceContext<ResourceComponent<?>> context;

    @Override
    public AvailabilityType getAvailability() {
        return AvailabilityType.UP;
    }

    @Override
    public BundleDeployResult deployBundle(BundleDeployRequest request) {
        ServerCommandRunner runner = ServerCommandRunner
            .onServer(request.getReferencedConfiguration(), AS7Mode.valueOf(request.getDestinationTarget().getPath()),
                context.getSystemInformation());

        ProcessExecutionResults results = runner.runCliCommand("patch apply --path=" +
            request.getPackageVersionFiles().values().iterator().next().getAbsolutePath());

        BundleDeployResult result = new BundleDeployResult();

        BundleManagerProvider bmp = request.getBundleManagerProvider();
        BundleResourceDeployment rd = request.getResourceDeployment();

        switch (handleExecutionResults(results, bmp, rd, true)) {
        case EXECUTION_ERROR:
            result.setErrorMessage("Error while trying to execute patch command: " + results.getError().getMessage());
            return result;
        case TIMEOUT:
            result.setErrorMessage("Patch application timed out." + results.getCapturedOutput());
            return result;
        case ERROR:
            result.setErrorMessage("Patch application failed with error code " + results.getExitCode() + ".");
            return result;
        }

        audit(bmp, rd, "Restart", "Stop", null, "Patch successfully applied. Stopping the server to restart.");
        results = runner.shutdownServer();
        switch (handleExecutionResults(results, bmp, rd, true)) {
        case EXECUTION_ERROR:
            result.setErrorMessage("Error trying to stop the server: " + results.getError().getMessage());
            return result;
        case TIMEOUT:
            result.setErrorMessage("Restarting the server after patch application timed out. Captured output: " +
                    results.getCapturedOutput());
            return result;
        case ERROR:
            result.setErrorMessage("Restarting the server after patch application failed with error code " +
                    results.getExitCode() + ".");
            return result;
        }

        audit(bmp, rd, "Restart", "Start", null,
            "Patch successfully applied. Starting the server to finish the restart.");

        results = runner.startServer();
        switch (handleExecutionResults(results, bmp, rd, false)) {
        case EXECUTION_ERROR:
            result.setErrorMessage("Error trying to start the server." + results.getError().getMessage());
            return result;
        case ERROR:
            result.setErrorMessage(
                "Restarting the server after patch application failed with error code " + results.getExitCode() + ".");
            return result;
        // ignore timeout, because starting the server actually would always be detected as doing it, because the start
        // script never stops...
        }

        try {
            ASConnection connection = new ASConnection(
                ASConnectionParams.createFrom(new ServerPluginConfiguration(request.getReferencedConfiguration())));
            waitForServerToStart(connection);
        } catch (InterruptedException e) {
            result.setErrorMessage("Interrupted while waiting for the server to start up after applying the patch");

            Thread.currentThread().interrupt();
            return result;
        }

        return result;
    }

    @Override
    public BundlePurgeResult purgeBundle(BundlePurgeRequest request) {
        BundlePurgeResult result = new BundlePurgeResult();

        PropertySimple patchIdProp = request.getLiveResourceDeployment().getBundleDeployment().getConfiguration()
            .getSimple("patchId");
        PropertySimple allPatchIdsProp = request.getLiveResourceDeployment().getBundleDeployment().getConfiguration()
            .getSimple("allPatchIds");

        String[] pids;
        List<String> patchCommands = new ArrayList<String>();
        if (patchIdProp != null && patchIdProp.getStringValue() != null) {
            patchCommands.add(PATCH_ROLLBACK_COMMAND + patchIdProp.getStringValue());
            pids = new String[1];
            pids[0] = patchIdProp.getStringValue();
        } else if (allPatchIdsProp != null && allPatchIdsProp.getStringValue() != null) {
            pids = allPatchIdsProp.getStringValue().split("\\|");
            for (String pid : pids) {
                patchCommands.add(PATCH_ROLLBACK_COMMAND + pid);
            }

            //we need to rollback in the reverse order to patch definition order in which they have been applied.
            Collections.reverse(patchCommands);
            pids = patchCommands.toArray(pids);
        } else {
            result.setErrorMessage("Could not determine what patch to purge from the bundle configuration.");
            return result;
        }

        //patches need to be applied 1 by 1, restarting the server between each application
        ServerCommandRunner runner = ServerCommandRunner
            .onServer(request.getReferencedConfiguration(), AS7Mode.valueOf(request.getDestinationTarget().getPath()),
                context.getSystemInformation());

        BundleManagerProvider bmp = request.getBundleManagerProvider();
        BundleResourceDeployment rd = request.getLiveResourceDeployment();

        int i = 0;
        for (String command : patchCommands) {
            ProcessExecutionResults results = runner.runCliCommand(command);
            switch (handleExecutionResults(results, bmp, rd, true)) {
            case EXECUTION_ERROR:
                result.setErrorMessage(fullErrorMessage("Error trying to run patch rollback: " +
                    results.getError().getMessage(), pids, i - 1, "rolled back"));
                return result;
            case TIMEOUT:
                result.setErrorMessage(fullErrorMessage("Patch rollback timed out. Captured output: " +
                    results.getCapturedOutput(), pids, i - 1, "rolled back"));
                return result;
            case ERROR:
                result.setErrorMessage(fullErrorMessage("Patch rollback failed with error code " + results.getExitCode()
                    + ".", pids, i - 1, "rolled back"));
                return result;
            }

            audit(bmp, rd, "Restart", "Stop", null,
                "Patch '" + pids[i] + "' successfully rolled back. Stopping the server to restart.");

            results = runner.shutdownServer();
            switch (handleExecutionResults(results, bmp, rd, true)) {
            case EXECUTION_ERROR:
                result.setErrorMessage(fullErrorMessage(
                    "Error trying to shutdown the server after rolling back the patch '" + pids[i] + "': " +
                        results.getError().getMessage(), pids, i, "rolled back"));
                return result;
            case TIMEOUT:
                result.setErrorMessage(
                    fullErrorMessage("Restarting the server after patch rollback timed out. Captured output: " +
                    results.getCapturedOutput(), pids, i, "rolled back"));
                return result;
            case ERROR:
                result.setErrorMessage(fullErrorMessage(
                    "Restarting the server after patch rollback failed with error code " + results.getExitCode() + ".",
                    pids, i, "rolled back"));
                return result;
            }

            audit(bmp, rd, "Restart", "Start", null,
                "Patch '" + pids[i] + "' successfully rolled back. Starting the server to finish the restart.");

            results = runner.startServer();
            switch (handleExecutionResults(results, bmp, rd, false)) {
            case EXECUTION_ERROR:
                result.setErrorMessage("Error trying to start the server: " + results.getError().getMessage());
                return result;
            case ERROR:
                result.setErrorMessage(
                    "Restarting the server after patch rollback failed with error code " + results.getExitCode() + ".");
                return result;
            // ignore timeout, because starting the server actually would always be detected as doing it, because the start
            // script never stops...
            }

            try {
                ASConnection connection = new ASConnection(
                    ASConnectionParams.createFrom(new ServerPluginConfiguration(request.getReferencedConfiguration())));
                waitForServerToStart(connection);
            } catch (InterruptedException e) {
                if (i < pids.length - 1) {
                    String message = fullErrorMessage("Interrupted while waiting for the server to start up after rolling back patch '" +
                        pids[i] + "'.", pids, i, "rolled back");
                    result.setErrorMessage(message);

                    Thread.currentThread().interrupt();
                    return result;
                }
            }

            ++i;
        }

        return result;
    }

    @Override
    public void start(ResourceContext<ResourceComponent<?>> context) {
        this.context = context;
    }

    @Override
    public void stop() {
    }

    private boolean waitForServerToStart(ASConnection connection) throws InterruptedException {
        boolean up = false;
        while (!up) {
            Operation op = new ReadAttribute(new Address(), "release-version");
            try {
                Result res = connection.execute(op);
                if (res.isSuccess()) { // If op succeeds, server is not down
                    up = true;
                }
            } catch (Exception e) {
                //do absolutely nothing
                //if an exception is thrown that means the server is still down, so consider this
                //a single failed attempt, equivalent to res.isSuccess == false
            }

            if (!up) {
                if (context.getComponentInvocationContext().isInterrupted()) {
                    // Operation canceled or timed out
                    throw new InterruptedException();
                }
                Thread.sleep(SECONDS.toMillis(1));
            }
        }
        return true;
    }

    private ExecutionResult handleExecutionResults(ProcessExecutionResults results, BundleManagerProvider bmp,
        BundleResourceDeployment resourceDeployment, boolean doAudit) {
        ExecutionResult ret = ExecutionResult.OK;

        if (results.getError() != null) {
            ret = ExecutionResult.EXECUTION_ERROR;
        } else if (results.getExitCode() == null) {
            ret = ExecutionResult.TIMEOUT;
        } else if (results.getExitCode() != 0) {
            ret = ExecutionResult.ERROR;
        }

        if (doAudit) {
            audit(bmp, resourceDeployment, "Output", ret == ExecutionResult.OK ? "Standard" : "Error", ret.status(),
                results.getCapturedOutput());
        }

        return ret;
    }

    private void audit(BundleManagerProvider bmp, BundleResourceDeployment resourceDeployment, String action,
        String info, BundleResourceDeploymentHistory.Status status, String message) {

        try {
            bmp.auditDeployment(resourceDeployment, action, info,
                BundleResourceDeploymentHistory.Category.AUDIT_MESSAGE, status, message, null);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed to report audit deployment.", e);
            }
        }
    }

    private enum ExecutionResult {
        EXECUTION_ERROR, TIMEOUT, ERROR, OK;

        public BundleResourceDeploymentHistory.Status status() {
            return this == OK ? BundleResourceDeploymentHistory.Status.SUCCESS :
                BundleResourceDeploymentHistory.Status.FAILURE;
        }
    }

    private String fullErrorMessage(String basicMessage, String[] patchIds, int attemptedPatchIdIndex, String action) {
        String message = basicMessage;

        if (attemptedPatchIdIndex > 0) {
            message += " The following patches were successfully " + action + ": " + StringUtil.collectionToString(
                Arrays.asList(patchIds).subList(0, attemptedPatchIdIndex)) + ".";
        }

        if (attemptedPatchIdIndex < patchIds.length - 1) {
            message += " The following patches were NOT " + action + ": " +
                StringUtil.collectionToString(Arrays.asList(patchIds).subList(attemptedPatchIdIndex + 1, patchIds.length))
                + ".";
        }

        return message;
    }
}
