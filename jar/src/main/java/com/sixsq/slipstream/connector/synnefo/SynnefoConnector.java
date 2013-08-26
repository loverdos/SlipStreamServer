package com.sixsq.slipstream.connector.synnefo;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.sixsq.slipstream.configuration.Configuration;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.openstack.OpenStackConnector;
import com.sixsq.slipstream.connector.openstack.OpenStackUserParametersFactory;
import com.sixsq.slipstream.exceptions.*;
import com.sixsq.slipstream.persistence.ImageModule;
import com.sixsq.slipstream.persistence.Run;
import com.sixsq.slipstream.persistence.RunType;
import com.sixsq.slipstream.persistence.User;
import org.jclouds.Constants;
import org.jclouds.compute.Utils;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.NodeMetadataBuilder;
import org.jclouds.domain.Credentials;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.domain.Address;
import org.jclouds.openstack.nova.v2_0.domain.Server;
import org.jclouds.openstack.nova.v2_0.domain.ServerCreated;
import org.jclouds.openstack.nova.v2_0.options.CreateServerOptions;
import org.jclouds.ssh.SshClient;
import org.jclouds.sshj.config.SshjSshClientModule;

import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Connector for synnefo.org.
 */
public class SynnefoConnector extends OpenStackConnector {
    private static Logger log = Logger.getLogger(SynnefoConnector.class.toString());

    public static final String DEFAULT_CLOUD_SERVICE_NAME = "okeanos";

    // TODO Change this accordingly whenwe have enough feedback from sixsq
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
        System.out.println("launchDeployment()");
        Properties overrides = new Properties();
        NovaApi client = getClient(user, overrides);

        try {
            Configuration configuration = Configuration.getInstance();

            ImageModule imageModule = (run.getType() == RunType.Machine) ? ImageModule
                .load(run.getModuleResourceUrl()) : null;

            String region = configuration.getRequiredProperty(constructKey("cloud.connector.service.region"));
            System.out.println("region = " + region);
            String imageId = (run.getType() == RunType.Orchestration) ? getOrchestratorImageId() : getImageId(run);
            System.out.println("imageId = " + imageId);

            String instanceName = (run.getType() == RunType.Orchestration) ? getOrchestratorName(run) : imageModule.getShortName();
            System.out.println("instanceName = " + instanceName);

            String flavorName = (run.getType() == RunType.Orchestration) ? configuration
                .getRequiredProperty(constructKey("cloud.connector.orchestrator.instance.type"))
                : getInstanceType(imageModule);
            System.out.println("flavorName = " + flavorName);
            String flavorId = getFlavorId(client, region, flavorName);
            System.out.println("flavorId = " + flavorId);
            String[] securityGroups = (run.getType() == RunType.Orchestration) ? "default".split(",")
                : user.getParameterValue(constructKey(OpenStackUserParametersFactory.SECURITY_GROUP), "").split(",");
            System.out.println("securityGroups = " + Arrays.toString(securityGroups));

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

            final String instanceId = server.getId();
            String ipAddress = "";

            while(ipAddress.isEmpty()) {
                try {
                    System.out.println("ipAddress is empty, sleeping...");
                    Thread.sleep(3000);
                }
                catch(InterruptedException ignored) {
                }
                ipAddress = getIpAddress(client, region, instanceId);
            }

            System.out.println("ipAddress = " + ipAddress);

            updateInstanceIdAndIpOnRun(run, instanceId, ipAddress);

            if(run.getType() != RunType.Orchestration) {
                return;
            }

            // Now run the initial script for the Orchestration VM
            System.out.println("====== INITIAL SCRIPT ========");
            final String orchestratorScript = super.createContextualizationData(run, user, configuration);
            final String nodeUsername = "root";
            final String nodePassword = server.getAdminPass();
            final String nodeId = String.format("%s/%s", region, instanceId);
            System.out.println("nodeId = " + nodeId);
            final NodeMetadata baseNodeMetadata = getComputeService().getNodeMetadata(nodeId);
            final NodeMetadata nodeMetadata = NodeMetadataBuilder.fromNodeMetadata(baseNodeMetadata).
                credentials(LoginCredentials.fromCredentials(new Credentials(nodeUsername, nodePassword))).
                build();
            final Utils sshUtils = getComputeServiceContext().getUtils();
            final SshClient sshClient = sshUtils.sshForNode().apply(nodeMetadata);
            System.out.println("sshClient = " + sshClient);
            try {
                System.out.println("Executing script");
                ExecResponse response = sshClient.exec(orchestratorScript);
                System.out.println("response = " + response);
            } finally {
                try { if(sshClient != null) sshClient.disconnect(); }
                catch(Exception e) { e.printStackTrace(); }
            }
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
