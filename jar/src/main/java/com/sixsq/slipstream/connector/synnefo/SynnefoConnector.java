package com.sixsq.slipstream.connector.synnefo;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.sixsq.slipstream.configuration.Configuration;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.openstack.OpenStackConnector;
import com.sixsq.slipstream.connector.openstack.OpenStackImageParametersFactory;
import com.sixsq.slipstream.connector.openstack.OpenStackUserParametersFactory;
import com.sixsq.slipstream.exceptions.*;
import com.sixsq.slipstream.persistence.ImageModule;
import com.sixsq.slipstream.persistence.Run;
import com.sixsq.slipstream.persistence.RunType;
import com.sixsq.slipstream.persistence.User;
import org.jclouds.Constants;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.domain.Address;
import org.jclouds.openstack.nova.v2_0.domain.Server;
import org.jclouds.openstack.nova.v2_0.domain.ServerCreated;
import org.jclouds.openstack.nova.v2_0.options.CreateServerOptions;
import org.jclouds.sshj.config.SshjSshClientModule;

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

    public SynnefoConnector(String cloudServiceName) {
        super(cloudServiceName);
    }

    public Connector copy() {
        return new SynnefoConnector(getConnectorInstanceName());
    }

    public String getCloudServiceName() {
        return SynnefoConnector.DEFAULT_CLOUD_SERVICE_NAME;
    }

    public String getJcloudsDriverName() {
        return OpenStackConnector.JCLOUDS_DRIVER_NAME;
    }

    @Override
    protected Iterable<com.google.inject.Module> getContextBuilderModules() {
        return ImmutableSet.<com.google.inject.Module>of(
            new SLF4JLoggingModule(),
            new SshjSshClientModule()
        );
    }

    @Override
    protected void updateContextBuilderPropertiesOverrides(User user, Properties overrides) throws ValidationException {
        super.updateContextBuilderPropertiesOverrides(user, overrides);

        // TODO Obtain the version from some user-configurable property
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

    // We use the same structure of the parent method and just change the details that are different
    // for Synnefo. In particular, we do not rely on the KeyPair OpenStack extension, but run scripts
    // via SSH directly.
    // NOTE From the currently provided Okeanos images, we cannot login to Ubuntu as root.
    //      This means that we must rely on a custom image, from which we can spawn Ubuntu servers
    //      for use by SlipStream.
    //      Using kamaki, after we create the custom image by just copying the official
    //      Ubuntu Server one, we enable root:
    //        $ kamaki image compute properties set CUSTOM_IMAGE_ID users="user root"
    @Override
    protected void launchDeployment(Run run, User user) throws ServerExecutionEnginePluginException, ClientExecutionEnginePluginException, InvalidElementException, ValidationException {
        Properties overrides = new Properties();
        NovaApi client = getClient(user, overrides);

        try {
            Configuration configuration = Configuration.getInstance();

            ImageModule imageModule = (run.getType() == RunType.Machine) ? ImageModule
                .load(run.getModuleResourceUrl()) : null;

            String region = configuration.getRequiredProperty(constructKey(OpenStackUserParametersFactory.SERVICE_REGION_PARAMETER_NAME));
            String imageId = (run.getType() == RunType.Orchestration) ? getOrchestratorImageId(user) : getImageId(run, user);

            String instanceName = (run.getType() == RunType.Orchestration) ? getOrchestratorName(run) : imageModule.getShortName();

            String flavorName = (run.getType() == RunType.Orchestration) ? configuration
                .getRequiredProperty(constructKey(OpenStackUserParametersFactory.SERVICE_REGION_PARAMETER_NAME))
                : getInstanceType(imageModule);
            String flavorId = getFlavorId(client, region, flavorName);
            String[] securityGroups = (run.getType() == RunType.Orchestration) ? "default".split(",")
                : getParameterValue(OpenStackImageParametersFactory.SECURITY_GROUP, imageModule).split(",");

            String instanceData = "\n\nStarting instance on region '" + region + "'\n";
            instanceData += "Image id: " + imageId + "\n";
            instanceData += "Instance type: " + flavorName + "\n";
            log.info(instanceData);

            CreateServerOptions options = CreateServerOptions.Builder
                .securityGroupNames(securityGroups);

            ServerCreated server = null;
            try {
                server = client.getServerApiForZone(region).create(instanceName, imageId, flavorId, options);
            }
            catch(Exception e) {
                e.printStackTrace();
                throw (new ServerExecutionEnginePluginException(e.getMessage()));
            }

            String instanceId = server.getId();
            String ipAddress = "";

            while(ipAddress.isEmpty()) {
                try {
                    Thread.sleep(1000);
                }
                catch(InterruptedException e) {

                }
                ipAddress = getIpAddress(client, region, instanceId);
            }

            updateInstanceIdAndIpOnRun(run, instanceId, ipAddress);

            if(run.getType() != RunType.Orchestration) {
                return;
            }

            // Now run the initial script for the Orchestration VM
            final String initialScript = super.createContextualizationData(run, user, configuration);
        }
        catch(com.google.common.util.concurrent.UncheckedExecutionException e) {
            e.printStackTrace();
            if(e.getCause() != null && e.getCause().getCause() != null && e.getCause().getCause().getClass() == org.jclouds.rest.AuthorizationException.class) {
                throw new ServerExecutionEnginePluginException("Authorization exception for the cloud: " + getConnectorInstanceName() + ". Please check your credentials.");
            }
            else if(e.getCause() != null && e.getCause().getCause() != null && e.getCause().getCause().getClass() == java.lang.IllegalArgumentException.class) {
                throw new ServerExecutionEnginePluginException("Error setting execution instance for the cloud " + getConnectorInstanceName() + ": " + e.getCause().getCause().getMessage());
            }
            else {
                throw new ServerExecutionEnginePluginException(e.getMessage());
            }
        }
        catch(SlipStreamException e) {
            e.printStackTrace();
            throw (new ServerExecutionEnginePluginException(
                "Error setting execution instance for the cloud " + getConnectorInstanceName() + ": " + e.getMessage()));
        }
        catch(Exception e) {
            e.printStackTrace();
            throw new ServerExecutionEnginePluginException(e.getMessage());
        }
        finally {
            closeContext();
        }
    }
}
