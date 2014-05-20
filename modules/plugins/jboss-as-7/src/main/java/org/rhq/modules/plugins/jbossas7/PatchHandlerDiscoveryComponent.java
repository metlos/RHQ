package org.rhq.modules.plugins.jbossas7;

import java.util.Collections;
import java.util.Set;

import org.rhq.core.domain.configuration.Configuration;
import org.rhq.core.pluginapi.inventory.DiscoveredResourceDetails;
import org.rhq.core.pluginapi.inventory.ResourceComponent;
import org.rhq.core.pluginapi.inventory.ResourceDiscoveryComponent;
import org.rhq.core.pluginapi.inventory.ResourceDiscoveryContext;

/**
 * @author Lukas Krejci
 * @since 4.12
 */
public class PatchHandlerDiscoveryComponent implements ResourceDiscoveryComponent<ResourceComponent<?>> {
    @Override
    public Set<DiscoveredResourceDetails> discoverResources(ResourceDiscoveryContext<ResourceComponent<?>> context) {

        Configuration pluginConfig = context.getDefaultPluginConfiguration();
        DiscoveredResourceDetails details = new DiscoveredResourceDetails(context.getResourceType(), "WflyPatchHandler",
            "Wildfly/JBoss EAP Patch Handler", null, null, pluginConfig, null);

        return Collections.singleton(details);
    }
}
