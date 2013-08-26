package com.sixsq.slipstream.connector.openstack;

/*
 * +=================================================================+
 * SlipStream Server (WAR)
 * =====
 * Copyright (C) 2013 SixSq Sarl (sixsq.com)
 * =====
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -=================================================================-
 */

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.sixsq.slipstream.configuration.Configuration;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.Credentials;
import com.sixsq.slipstream.connector.JCloudsConnectorBase;
import com.sixsq.slipstream.exceptions.*;
import com.sixsq.slipstream.persistence.*;
import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.openstack.keystone.v2_0.config.CredentialTypes;
import org.jclouds.openstack.keystone.v2_0.config.KeystoneProperties;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.NovaAsyncApi;
import org.jclouds.openstack.nova.v2_0.domain.Address;
import org.jclouds.openstack.nova.v2_0.domain.Server;
import org.jclouds.openstack.nova.v2_0.domain.ServerCreated;
import org.jclouds.openstack.nova.v2_0.options.CreateServerOptions;
import org.jclouds.openstack.v2_0.domain.Resource;
import org.jclouds.rest.RestContext;

import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;


public class OpenStackConnector extends
		JCloudsConnectorBase<NovaApi, NovaAsyncApi> {

	private static Logger log = Logger.getLogger(OpenStackConnector.class
			.toString());

	// TODO Move this property to the superclass (JCloudsConnectorBase)
	private RestContext<NovaApi, NovaAsyncApi> context;

    private ComputeServiceContext computeServiceContext;

	public static final String CLOUD_SERVICE_NAME = "openstack";
	public static final String JCLOUDS_DRIVER_NAME = "openstack-nova";


    public OpenStackConnector() {
		this(CLOUD_SERVICE_NAME);
	}
	
	public OpenStackConnector(String instanceName){
		super(instanceName);
	}
	
	public String getCloudServiceName() {
		return CLOUD_SERVICE_NAME;
	}

	public String getJcloudsDriverName() {
		return JCLOUDS_DRIVER_NAME;
	}
	
	@Override
	public Run launch(Run run, User user)
			throws SlipStreamException {

		switch (run.getType()) {
		case Machine:
			launchDeployment(run, user);
			break;
		case Orchestration:
			launchDeployment(run, user);
			break;
		default:
			throw (new ServerExecutionEnginePluginException(
					"Cannot submit type: " + run.getCategory() + " yet!!"));
		}

		return run;
	}

	@Override
	public Map<String, ServiceConfigurationParameter> getServiceConfigurationParametersTemplate()
			throws ValidationException {
		return new OpenStackSystemConfigurationParametersFactory(getConnectorInstanceName())
				.getParameters();
	}

	@Override
	public Map<String, UserParameter> getUserParametersTemplate()
			throws ValidationException {
		return new OpenStackUserParametersFactory(getConnectorInstanceName()).getParameters();
	}

	@Override
	public Map<String, ModuleParameter> getImageParametersTemplate()
			throws ValidationException {
		return new OpenStackImageParametersFactory(getConnectorInstanceName()).getParameters();
	}
	
	@Override
	protected String constructKey(String key) throws ValidationException {
		return new OpenStackUserParametersFactory(getConnectorInstanceName()).constructKey(key);
	}

	@Override
	public Credentials getCredentials(User user) {
		return new OpenStackCredentials(user, getConnectorInstanceName());
	}

	@Override
	protected String getOrchestratorImageId() throws ConfigurationException, ValidationException {
		return Configuration.getInstance().getRequiredProperty(constructKey("cloud.connector.orchestrator.imageid"));
	}

	@Override
	public void terminate(Run run, User user) throws SlipStreamException {
		NovaApi client = getClient(user);

		Configuration configuration = Configuration.getInstance();
		String region = configuration
				.getRequiredProperty(constructKey("cloud.connector.service.region"));

		for (String instanceId : getCloudNodeInstanceIds(run)) {
			if (instanceId == null)
				continue;
			client.getServerApiForZone(region).delete(instanceId);
		}

	}

	@Override
	public Properties describeInstances(User user) throws SlipStreamException {
		Properties statuses = new Properties();

		NovaApi client = getClient(user);

		Configuration configuration = Configuration.getInstance();
		String region = configuration.getRequiredProperty(constructKey("cloud.connector.service.region"));

		FluentIterable<? extends Server> instances;
		try {
			instances = client.getServerApiForZone(region).listInDetail().concat();
		} catch (com.google.common.util.concurrent.UncheckedExecutionException e){
			if(e.getCause() != null && e.getCause().getCause() != null && e.getCause().getCause().getClass() == org.jclouds.rest.AuthorizationException.class){
				throw new SlipStreamClientException("Authorization exception for the cloud: " + getConnectorInstanceName() + ". Please check your credentials.");
			}else{
				throw new SlipStreamClientException(e.getMessage());
			}
		} catch (Exception e) {
			throw new ServerExecutionEnginePluginException(e.getMessage());
		}
		
		for (Server instance : instances) {
			String status = instance.getStatus().value().toLowerCase();
			String taskState = (instance.getExtendedStatus().isPresent()) ? instance
					.getExtendedStatus().orNull().getTaskState()
					: null;
			if (taskState != null)
				status += " - " + taskState;
			statuses.put(instance.getId(), status);
		}

		// closeContext();

		return statuses;
	}

	// TODO Move this method to the superclass (JCloudsConnectorBase)
	protected NovaApi getClient(User user) throws InvalidElementException, ValidationException {
		return getClient(user, new Properties());
	}

	protected NovaApi getClient(User user, Properties overrides) throws InvalidElementException, ValidationException {
        updateContextBuilderPropertiesOverrides(user, overrides);

        final ContextBuilder contextBuilder = updateContextBuilder(newContextBuilder(), user, overrides);
        this.computeServiceContext = contextBuilder.buildView(ComputeServiceContext.class);
		this.context = this.computeServiceContext.unwrap();
		return this.context.getApi();
	}

    protected ContextBuilder newContextBuilder() {
        return ContextBuilder.newBuilder(getJcloudsDriverName());
    }

    protected Iterable<com.google.inject.Module> getContextBuilderModules() {
        return ImmutableSet.of();
    }

    protected ContextBuilder updateContextBuilder(ContextBuilder contextBuilder, User user, Properties overrides) {
        return contextBuilder
                //.endpoint(getEndpoint(user))
                .modules(getContextBuilderModules())
                .credentials(getKey(user), getSecret(user))
                .overrides(overrides);
    }

    protected void updateContextBuilderPropertiesOverrides(User user, Properties overrides) throws ValidationException {
        if (overrides == null) {
            throw new NullPointerException("overrides");
        }

        overrides.setProperty(Constants.PROPERTY_ENDPOINT, user.getParameterValue(constructKey(OpenStackUserParametersFactory.KEYSTONE_URL),""));
        overrides.setProperty(Constants.PROPERTY_RELAX_HOSTNAME, "true");
        overrides.setProperty(Constants.PROPERTY_TRUST_ALL_CERTS, "true");
        overrides.setProperty(KeystoneProperties.CREDENTIAL_TYPE, CredentialTypes.PASSWORD_CREDENTIALS);
        overrides.setProperty(KeystoneProperties.REQUIRES_TENANT, "true");
        overrides.setProperty(KeystoneProperties.TENANT_NAME, user.getParameterValue(constructKey(OpenStackUserParametersFactory.TENANT_NAME), ""));
        //overrides.setProperty(Constants.PROPERTY_API_VERSION, "X.X");
    }

    // TODO Move this method to the superclass (JCloudsConnectorBase)
	protected void closeContext() {
		context.close();
        computeServiceContext.close();
	}

	protected void launchDeployment(Run run, User user)
			throws ServerExecutionEnginePluginException, ClientExecutionEnginePluginException, InvalidElementException, ValidationException {

		Properties overrides = new Properties();
		NovaApi client = getClient(user, overrides);

		try {
			Configuration configuration = Configuration.getInstance();
			ImageModule imageModule = (run.getType() == RunType.Machine) ? ImageModule
					.load(run.getModuleResourceUrl()) : null;

			String region = configuration.getRequiredProperty(constructKey("cloud.connector.service.region"));
			String imageId = (run.getType() == RunType.Orchestration)? getOrchestratorImageId() : getImageId(run);
			
			String instanceName = (run.getType() == RunType.Orchestration) ? getOrchestratorName(run) : imageModule.getShortName();
			
			String flavorName = (run.getType() == RunType.Orchestration) ? configuration
					.getRequiredProperty(constructKey("cloud.connector.orchestrator.instance.type"))
					: getInstanceType(imageModule);
			String flavorId = getFlavorId(client, region, flavorName);
			String userData = (run.getType() == RunType.Orchestration) ? createContextualizationData(run, user, configuration) : "";
			String keyPairName = user.getParameterValue(
					constructKey(OpenStackUserParametersFactory.KEYPAIR_NAME),
					"");
			String[] securityGroups = (run.getType() == RunType.Orchestration) ? "default".split(",")
					: user.getParameterValue(constructKey(OpenStackUserParametersFactory.SECURITY_GROUP), "").split(",");

			String instanceData = "\n\nStarting instance on region '" + region + "'\n";
			instanceData += "Image id: " + imageId + "\n";
			instanceData += "Instance type: " + flavorName + "\n";
			instanceData += "Keypair: " + keyPairName + "\n";
			instanceData += "Context:\n" + userData + "\n";
			log.info(instanceData);

			CreateServerOptions options = CreateServerOptions.Builder
					.keyPairName(keyPairName)
					.securityGroupNames(securityGroups);
			if (run.getType() == RunType.Orchestration)
				options.userData(userData.getBytes());

			ServerCreated server = null;
			try {
				server = client.getServerApiForZone(region).create(instanceName, imageId, flavorId, options);
			} catch (Exception e) {
				e.printStackTrace();
				throw (new ServerExecutionEnginePluginException(e.getMessage()));
			}

			String instanceId = server.getId();
			String ipAddress = "";

			while (ipAddress.isEmpty()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {

				}
				ipAddress = getIpAddress(client, region, instanceId);
			}
			
			updateInstanceIdAndIpOnRun(run, instanceId, ipAddress);

		} catch (com.google.common.util.concurrent.UncheckedExecutionException e){
			if(e.getCause() != null && e.getCause().getCause() != null && e.getCause().getCause().getClass() == org.jclouds.rest.AuthorizationException.class){
				throw new ServerExecutionEnginePluginException("Authorization exception for the cloud: " + getConnectorInstanceName() + ". Please check your credentials.");
			}else if(e.getCause() != null && e.getCause().getCause() != null && e.getCause().getCause().getClass() == java.lang.IllegalArgumentException.class){
				throw new ServerExecutionEnginePluginException("Error setting execution instance for the cloud " + getConnectorInstanceName() + ": " + e.getCause().getCause().getMessage());
			}else{
				throw new ServerExecutionEnginePluginException(e.getMessage());
			}
		} catch (SlipStreamException e) {
			throw (new ServerExecutionEnginePluginException(
					"Error setting execution instance for the cloud " + getConnectorInstanceName() + ": " + e.getMessage()));
		} catch (Exception e){
			throw new ServerExecutionEnginePluginException(e.getMessage());
		}

		closeContext();
	}

	protected String createContextualizationData(Run run, User user,
			Configuration configuration) throws ConfigurationException,
			ServerExecutionEnginePluginException, SlipStreamClientException {

		String logfilename = "orchestrator.slipstream.log";
		String bootstrap = "/tmp/slipstream.bootstrap";

		String username = user.getName();
		String password = user.getPassword();
		if (password == null) {
			throw (new ServerExecutionEnginePluginException(
					"Missing password entry in user profile"));
		}

		String userData = "#!/bin/sh -e \n";

		userData += "# SlipStream contextualization script for VMs on Amazon. \n";
		userData += "export SLIPSTREAM_CLOUD=\"" + getCloudServiceName() + "\"\n";
		userData += "export SLIPSTREAM_CONNECTOR_INSTANCE=\"" + getConnectorInstanceName() + "\"\n";
		userData += "export SLIPSTREAM_NODENAME=\"" + getOrchestratorName(run) + "\"\n";
		userData += "export SLIPSTREAM_DIID=\"" + run.getName() + "\"\n";
		userData += "export SLIPSTREAM_REPORT_DIR=\"" + SLIPSTREAM_REPORT_DIR + "\"\n";
		userData += "export SLIPSTREAM_SERVICEURL=\"" + configuration.baseUrl + "\"\n";				
		userData += "export SLIPSTREAM_BUNDLE_URL=\"" + configuration.getRequiredProperty("slipstream.update.clienturl") + "\"\n";
		userData += "export SLIPSTREAM_BOOTSTRAP_BIN=\"" + configuration.getRequiredProperty("slipstream.update.clientbootstrapurl") + "\"\n";
		userData += "export LIBCLOUD_BUNDLE_URL=\"" + configuration.getRequiredProperty("cloud.connector.library.libcloud.url") + "\"\n";
		userData += "export OPENSTACK_SERVICE_TYPE=\"" + configuration.getRequiredProperty(constructKey("cloud.connector.service.type")) + "\"\n";
		userData += "export OPENSTACK_SERVICE_NAME=\"" + configuration.getRequiredProperty(constructKey("cloud.connector.service.name")) + "\"\n";
		userData += "export OPENSTACK_SERVICE_REGION=\"" + configuration.getRequiredProperty(constructKey("cloud.connector.service.region")) + "\"\n";
		userData += "export SLIPSTREAM_CATEGORY=\"" + run.getCategory().toString() + "\"\n";
		userData += "export SLIPSTREAM_USERNAME=\"" + username + "\"\n";
		userData += "export SLIPSTREAM_COOKIE=" + getCookieForEnvironmentVariable(username) + "\n";
		userData += "export SLIPSTREAM_VERBOSITY_LEVEL=\"" + getVerboseParameterValue(user) + "\"\n";

		userData += "mkdir -p " + SLIPSTREAM_REPORT_DIR + "\n"
				+ "wget --no-check-certificate -O " + bootstrap
				+ " $SLIPSTREAM_BOOTSTRAP_BIN > " + SLIPSTREAM_REPORT_DIR + "/"
				+ logfilename + " 2>&1 " + "&& chmod 0755 " + bootstrap + "\n"
				+ bootstrap + " slipstream-orchestrator >> "
				+ SLIPSTREAM_REPORT_DIR + "/" + logfilename + " 2>&1\n";

		System.out.print(userData);

		return userData;
	}

	protected String getFlavorId(NovaApi client, String region, String flavorName)
			throws ConfigurationException, ServerExecutionEnginePluginException {

        String flavorListStr = "";

		FluentIterable<? extends Resource> flavors = client.getFlavorApiForZone(region).list().concat();
		for (Resource flavor : flavors) {
			if (flavor.getName().equals(flavorName))
				return flavor.getId();
			flavorListStr += " - " + flavor.getName() + "\n";
		}

		throw (new ServerExecutionEnginePluginException(
				"Provided OpenStack Flavor '" + flavorName + "' doesn't exist"
						+ "Supported Flavors: \n" + flavorListStr));
	}

	protected String getIpAddress(NovaApi client, String region, String instanceId) {

		FluentIterable<? extends Server> instances = client.getServerApiForZone(region).listInDetail().concat();
		for (Server instance : instances) {
			if (instance.getId().equals(instanceId)) {
				Multimap<String, Address> addresses = instance.getAddresses();

				if (addresses.containsKey("public"))
					return addresses.get("public").iterator().next().getAddr();
				if (addresses.containsKey("private"))
					return addresses.get("private").iterator().next().getAddr();

				break;
			}
		}

		return "";
	}

    protected ComputeServiceContext getComputeServiceContext() {
        return computeServiceContext;
    }

    protected RestContext<NovaApi, NovaAsyncApi> getContext() {
        return context;
    }

    protected ComputeService getComputeService() {
        return getComputeServiceContext().getComputeService();
    }
}
