package org.rhq.modules.plugins.jbossas7;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;

import org.rhq.core.pluginapi.inventory.DiscoveredResourceDetails;
import org.rhq.core.pluginapi.inventory.ResourceDiscoveryComponent;
import org.rhq.core.pluginapi.inventory.ResourceDiscoveryContext;
import org.rhq.core.system.ProcessExecutionResults;
import org.rhq.modules.plugins.jbossas7.helper.ServerCommandRunner;
import org.rhq.modules.plugins.jbossas7.json.Result;

/**
 * @author Lukas Krejci
 * @since 4.12
 */
public class PatchDiscoveryComponent implements ResourceDiscoveryComponent<StandaloneASComponent<?>> {

    private static final Log LOG = LogFactory.getLog(PatchDiscoveryComponent.class);

    @Override
    public Set<DiscoveredResourceDetails> discoverResources(ResourceDiscoveryContext<StandaloneASComponent<?>> context)
        throws Exception {

        StandaloneASComponent<?> server = context.getParentResourceComponent();
        String serverDetails = context.getParentResourceContext().getResourceDetails();
        ServerCommandRunner runner = ServerCommandRunner
            .onServer(server.pluginConfiguration, server.getMode(), context.getSystemInformation());

        ProcessExecutionResults results = runner.runCliCommand("patch history");
        if (results.getError() != null || results.getExitCode() == null || results.getExitCode() != 0) {
            if (LOG.isDebugEnabled()) {
                if (results.getError() == null) {
                    LOG.debug(
                        "Failed to obtain the patch history while discovering patches on " + serverDetails +
                            ". Exit code: " + results.getExitCode() + ", output:\n" + results.getCapturedOutput()
                    );
                } else {
                    LOG.debug("Failed to obtain the patch history while discovering patches on " + serverDetails,
                        results.getError());
                }
            } else if (results.getError() != null) {
                LOG.info("Failed to obtain the patch history while discovering patches on " + serverDetails + ": " +
                    results.getError().getMessage());
            }
            return Collections.emptySet();
        }

        ObjectMapper mapper = new ObjectMapper();

        Result result = mapper.readValue(results.getCapturedOutput(), Result.class);
        if (!result.isSuccess()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("'patch history' command didn't succeed while discovering patches on " +
                    serverDetails + ": " + result);
            }
            return Collections.emptySet();
        }

        // expecting a list of maps of string->string
        if (!(result.getResult() instanceof List)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Unexpected patch history results during patch discovery. Expected list but found " +
                    (result.getResult() == null ? "null" : result.getResult().getClass().toString()));
            }

            return Collections.emptySet();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, String>> patches = (List<Map<String, String>>) result.getResult();

        if (patches.isEmpty()) {
            return Collections.emptySet();
        }

        Set<DiscoveredResourceDetails> ret = new HashSet<DiscoveredResourceDetails>();

        for(Map<String, String> patchDescr : patches) {
            String patchId = patchDescr.get("patch-id");

            DiscoveredResourceDetails details = new DiscoveredResourceDetails(context.getResourceType(), patchId,
                patchId, null, null, context.getDefaultPluginConfiguration(), null);
            ret.add(details);
        }

        return ret;
    }
}
