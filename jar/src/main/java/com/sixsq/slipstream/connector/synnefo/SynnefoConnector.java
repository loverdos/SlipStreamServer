package com.sixsq.slipstream.connector.synnefo;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.Multimap;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.openstack.OpenStackConnector;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.persistence.User;
import org.jclouds.Constants;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.domain.Address;
import org.jclouds.openstack.nova.v2_0.domain.Server;

import java.util.Collection;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Connector for synnefo.org.
 */
public class SynnefoConnector extends OpenStackConnector {
    private static Logger log = Logger.getLogger(SynnefoConnector.class.toString());

    public static final String DEFAULT_CLOUD_SERVICE_NAME = "okeanos";
    public static final String CLOUDCONNECTOR_PYTHON_MODULENAME = "slipstream.cloudconnectors.openstack.OpenStackClientCloud";

    public SynnefoConnector() {
        this(DEFAULT_CLOUD_SERVICE_NAME);
    }

    public SynnefoConnector(String cloudServiceName){
        super(cloudServiceName);
    }

    public Connector copy(){
        return new SynnefoConnector(getConnectorInstanceName());
    }

    public String getCloudServiceName() {
        return SynnefoConnector.DEFAULT_CLOUD_SERVICE_NAME;
    }

    public String getJcloudsDriverName() {
        return OpenStackConnector.JCLOUDS_DRIVER_NAME;
    }

    @Override
    protected void updateContextBuilderPropertiesOverrides(User user, Properties overrides) throws ValidationException {
        super.updateContextBuilderPropertiesOverrides(user, overrides);
        overrides.setProperty(Constants.PROPERTY_API_VERSION, "v2.0");
    }

    @Override
    protected String getIpAddress(NovaApi client, String region, String instanceId) {
        final FluentIterable<? extends Server> instances = client.getServerApiForZone(region).listInDetail().concat();
        for(final Server instance : instances) {
            if(instance.getId().equals(instanceId)) {
                final Multimap<String, Address> addresses = instance.getAddresses();

                if(instance.getId().equals(instanceId)) {
                    if(addresses.size() > 0) {
                        if(addresses.containsKey("public")) {
                            final String addr = addresses.get("public").iterator().next().getAddr();
                            return addr;
                        }
                        else if(addresses.containsKey("private")) {
                            final String addr = addresses.get("private").iterator().next().getAddr();
                            return addr;
                        }
                        else {
                            final String instanceNetworkID = addresses.keySet().iterator().next();
                            final Collection<Address> instanceAddresses = addresses.get(instanceNetworkID);
                            if(instanceAddresses.size() > 0) {
                                final String addr = instanceAddresses.iterator().next().getAddr();
                                return addr;
                            }
                        }
                    }

                    break;
                }
            }
        }
        return "";
    }
}
