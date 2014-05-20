package org.rhq.modules.plugins.jbossas7;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;

import org.rhq.core.domain.configuration.Configuration;
import org.rhq.core.domain.configuration.PropertySimple;
import org.rhq.core.domain.measurement.AvailabilityType;
import org.rhq.core.domain.measurement.MeasurementDataTrait;
import org.rhq.core.domain.measurement.MeasurementReport;
import org.rhq.core.domain.measurement.MeasurementScheduleRequest;
import org.rhq.core.pluginapi.inventory.DeleteResourceFacet;
import org.rhq.core.pluginapi.inventory.ResourceComponent;
import org.rhq.core.pluginapi.inventory.ResourceContext;
import org.rhq.core.pluginapi.measurement.MeasurementFacet;
import org.rhq.core.system.ProcessExecutionResults;
import org.rhq.modules.plugins.jbossas7.helper.ServerCommandRunner;
import org.rhq.modules.plugins.jbossas7.json.Result;

/**
 * @author Lukas Krejci
 * @since 4.12
 */
public class PatchComponent implements ResourceComponent<StandaloneASComponent<?>>, DeleteResourceFacet,
    MeasurementFacet {

    private static final Log LOG = LogFactory.getLog(PatchComponent.class);

    private ResourceContext<StandaloneASComponent<?>> context;
    private final ObjectMapper mapper = new ObjectMapper();

    private String patchType;
    private String patchAppliedAt;

    @Override
    public AvailabilityType getAvailability() {
        readDetails();
        return patchType == null ? AvailabilityType.DOWN : AvailabilityType.UP;
    }

    @Override
    public void start(ResourceContext<StandaloneASComponent<?>> context) {
        this.context = context;
        readDetails();
    }

    @Override
    public void stop() {
    }

    @Override
    public void deleteResource() throws Exception {
        StringBuilder command = new StringBuilder("patch rollback --patch-id=");
        command.append(context.getResourceKey());

        Configuration config = context.getPluginConfiguration();
        appendParameter(command, config, "reset-configuration");
        appendParameter(command, config, "override-all");
        appendParameter(command, config, "override-modules");
        appendParameter(command, config, "override");
        appendParameter(command, config, "preserve");

        ProcessExecutionResults results = createRunner().runCliCommand(command.toString());

        if (results.getError() != null || results.getExitCode() == null || results.getExitCode() != 0) {
            //looks like stuff failed...
            if (results.getError() != null) {
                throw new Exception("Rolling back the patch " + context.getResourceKey() + " failed.",
                    results.getError());
            } else if (results.getExitCode() == null) {
                throw new Exception("Rolling back the patch timed out. Captured output of the rollback command: " +
                    results.getCapturedOutput());
            } else {
                throw new Exception("Rolling back the patch exited with an error code " + results.getExitCode() +
                    ". Captured output of the rollback command: " + results.getCapturedOutput());
            }
        }
    }

    @Override
    public void getValues(MeasurementReport report, Set<MeasurementScheduleRequest> metrics) throws Exception {
        for (MeasurementScheduleRequest r : metrics) {
            String name = r.getName();
            if ("patchId".equals(name)) {
                report.addData(new MeasurementDataTrait(r, context.getResourceKey()));
            } else if ("type".equals(r.getName()) && patchType != null) {
                report.addData(new MeasurementDataTrait(r, patchType));
            } else if ("appliedAt".equals(r.getName()) && patchAppliedAt != null) {
                report.addData(new MeasurementDataTrait(r, patchAppliedAt));
            }
        }
    }

    private void readDetails() {
        patchType = null;
        patchAppliedAt = null;

        ServerCommandRunner runner = createRunner();

        ProcessExecutionResults results = runner.runCliCommand("patch history");
        if (results.getError() != null || results.getExitCode() == null || results.getExitCode() != 0) {
            if (LOG.isDebugEnabled()) {
                if (results.getError() == null) {
                    LOG.debug(
                        "Failed to obtain the patch history of patch " + context.getResourceDetails() +
                            ". Exit code: " + results.getExitCode() + ", output:\n" + results.getCapturedOutput()
                    );
                } else {
                    LOG.debug("Failed to determine details of patch " + context.getResourceDetails(),
                        results.getError());
                }
            } else if (results.getError() != null) {
                LOG.info("Failed to determine details of patch " + context.getResourceDetails() + ": " +
                    results.getError().getMessage());
            }

            return;
        }

        try {
            Result result = mapper.readValue(results.getCapturedOutput(), Result.class);
            if (!result.isSuccess()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("'patch history' command didn't succeed while getting details of " +
                        context.getResourceDetails() + ": " + result);
                }
                return;
            }

            if (!(result.getResult() instanceof List)) {
                //hmm.. strange
                if (LOG.isDebugEnabled()) {
                    LOG.debug("'patch history' command returned unexpected results. Expected a list but got: " +
                        results.getCapturedOutput());
                }

                return;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> installedPatches = (List<Map<String, String>>) result.getResult();

            for (Map<String, String> p : installedPatches) {
                if (context.getResourceKey().equals(p.get("patch-id"))) {
                    patchType = p.get("type");
                    patchAppliedAt = p.get("applied-at");
                    return;
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to parse the patch history result while getting details of " +
                context.getResourceDetails() + ": " + e.getMessage());
        }
    }

    private ServerCommandRunner createRunner() {
        StandaloneASComponent<?> server = context.getParentResourceComponent();
        return ServerCommandRunner
            .onServer(server.pluginConfiguration, server.getMode(), context.getSystemInformation());
    }

    private void appendParameter(StringBuilder command, Configuration configuration, String parameterName) {
        PropertySimple prop = configuration.getSimple(parameterName);
        if (prop != null && prop.getStringValue() != null) {
            command.append(" --").append(parameterName).append("=").append(prop.getStringValue());
        }
    }
}
