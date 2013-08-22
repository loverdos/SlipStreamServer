package com.sixsq.slipstream.connector.stratuslab;

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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import com.sixsq.slipstream.configuration.Configuration;
import com.sixsq.slipstream.connector.CliConnectorBase;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.Credentials;
import com.sixsq.slipstream.connector.ExecutionControlUserParametersFactory;
import com.sixsq.slipstream.connector.UserParametersFactoryBase;
import com.sixsq.slipstream.exceptions.AbortException;
import com.sixsq.slipstream.exceptions.ConfigurationException;
import com.sixsq.slipstream.exceptions.InvalidElementException;
import com.sixsq.slipstream.exceptions.NotFoundException;
import com.sixsq.slipstream.exceptions.ServerExecutionEnginePluginException;
import com.sixsq.slipstream.exceptions.SlipStreamClientException;
import com.sixsq.slipstream.exceptions.SlipStreamException;
import com.sixsq.slipstream.exceptions.SlipStreamInternalException;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.persistence.DeploymentModule;
import com.sixsq.slipstream.persistence.ImageModule;
import com.sixsq.slipstream.persistence.ModuleCategory;
import com.sixsq.slipstream.persistence.ModuleParameter;
import com.sixsq.slipstream.persistence.Node;
import com.sixsq.slipstream.persistence.Run;
import com.sixsq.slipstream.persistence.RunType;
import com.sixsq.slipstream.persistence.ServiceConfigurationParameter;
import com.sixsq.slipstream.persistence.User;
import com.sixsq.slipstream.persistence.UserParameter;
import com.sixsq.slipstream.util.ProcessUtils;

public class StratusLabConnector extends CliConnectorBase {

	private static final String SLIPSTREAM_REPORT_DIR = "/tmp/slipstream/reports";

	private static final String EXTRADISK_NAME_VOLATILE = "volatile";
	private static final String EXTRADISK_NAME_READONLY = "readonly";
	private static final String EXTRADISK_NAME_PERSISTENT = "persistent";

	protected static final List<String> EXTRADISK_NAMES = Arrays.asList(
			EXTRADISK_NAME_VOLATILE, EXTRADISK_NAME_READONLY,
			EXTRADISK_NAME_PERSISTENT);

	public static final String CLOUD_SERVICE_NAME = "stratuslab";
	public static final String CLOUDCONNECTOR_PYTHON_MODULENAME = "slipstream.cloudconnectors.stratuslab.StratuslabClientCloud";

	public StratusLabConnector() {
		this(CLOUD_SERVICE_NAME);
	}

	public StratusLabConnector(String instanceName) {
		super(instanceName);
	}

	public Connector copy() {
		return new StratusLabConnector(getConnectorInstanceName());
	}
	
	public String getCloudServiceName() {
		return CLOUD_SERVICE_NAME;
	}

	@Override
	public Map<String, ServiceConfigurationParameter> getServiceConfigurationParametersTemplate()
			throws ValidationException {
		return new StratusLabSystemConfigurationParametersFactory(
				getConnectorInstanceName()).getParameters();
	}


	@Override
	public Run launch(Run run, User user) throws SlipStreamException {

		validate(run, user);

		String command;
		try {
			command = getRunInstanceCommand(run, user);
		} catch (IOException e) {
			throw (new SlipStreamException(
					"Failed getting run instance command", e));
		}

		String result;
		String[] commands = { "sh", "-c", command };
		try {
			result = ProcessUtils.execGetOutput(commands);
		} catch (IOException e) {
			e.printStackTrace();
			throw (new SlipStreamInternalException(e));
		} finally {
			deleteTempSshKeyFile();
		}

		String[] instanceData = parseRunInstanceResult(result);
		String instanceId = instanceData[0];
		String ipAddress = instanceData[1];

		updateInstanceIdAndIpOnRun(run, instanceId, ipAddress);

		return run;
	}

	private String getRunInstanceCommand(Run run, User user)
			throws InvalidElementException, ValidationException,
			SlipStreamClientException, IOException, ConfigurationException,
			ServerExecutionEnginePluginException {

		String context = createContextualizationData(run, user);
		String publicSshKey = getPublicSshKeyFileName(run, user);
		String imageId = getImageId(run, user);
		String vmName = getVmName(run);

		String extraDisksCommand = getExtraDisksCommand(run);

		return "/usr/bin/stratus-run-instance " + imageId + " --quiet --key "
				+ publicSshKey + " -u " + getKey(user) + " -p "
				+ getSecret(user) + " --endpoint " + getEndpoint(user)
				+ " --marketplace-endpoint " + getMarketplaceEndpoint(user)
				+ " --context " + context + " --vm-name " + vmName + ":"
				+ run.getName() + extraDisksCommand;
	}

	protected String getMarketplaceEndpoint(User user) throws ConfigurationException, ValidationException {
		return user.getParameter(constructKey(StratusLabUserParametersFactory.MARKETPLACE_ENDPOINT_PARAMETER_NAME)).getValue();
	}
	
	private String getVmName(Run run) {
		return run.getType() == RunType.Orchestration ? getOrchestratorName(run)
				: "machine";
	}

	private void validate(Run run, User user) throws ValidationException {
		validateCredentials(user);
		validateUserSshPublicKey(user);
		validateLaunch(run, user);
	}

	private void validateLaunch(Run run, User user)
			throws ValidationException {
		if (run.getCategory() == ModuleCategory.Image) {
			ImageModule image = ImageModule.load(run.getModuleResourceUrl());
			validateImageModule(image, user);
		}
		if (run.getCategory() == ModuleCategory.Deployment) {
			for (Node node : DeploymentModule.load(run.getModuleResourceUrl())
					.getNodes().values()) {
				validateImageModule(node.getImage(), user);
			}
		}
	}

	private void validateImageModule(ImageModule image, User user)
			throws ValidationException {
		validateParameters(image, user);
	}

	private void validateParameters(ImageModule image, User user)
			throws ValidationException {
		validateInstanceType(image, user);
	}

	private void validateInstanceType(ImageModule image, User user)
			throws ValidationException {

		String instanceType = getInstanceType(image);

		if (image.isBase()
				&& ImageModule.INSTANCE_TYPE_INHERITED.equals(instanceType)) {
			throw (new ValidationException(
					"Base image cannot have inherited instance type. Please review the instance type under Parameters -> "
							+ getConnectorInstanceName()));
		}

		if (instanceType == null
				&& (getRam(image) == null && getCpu(image) == null)) {
			throw new ValidationException(
					"Missing instance type or ram/cpu information. Please review the instance type under Parameters -> "
							+ getConnectorInstanceName() + " Or it's parents.");
		}
	}

	private void validateUserSshPublicKey(User user) throws ValidationException {
		String sshParameterName = ExecutionControlUserParametersFactory.CATEGORY
				+ "." + UserParametersFactoryBase.SSHKEY_PARAMETER_NAME;
		if (!isParameterDefined(user, sshParameterName)) {
			String errorMessageLastPart = getErrorMessageLastPart(user);
			throw (new ValidationException(
					"StratusLab SSH public key must be provided"
							+ errorMessageLastPart));
		}
	}

	protected boolean isParameterDefined(User user, String sshParameterName) {
		return user.getParameters().containsKey(sshParameterName)
				&& !("".equals(user.getParameter(sshParameterName).getValue()) || user
						.getParameter(sshParameterName).getValue() == null);
	}


	private String createContextualizationData(Run run, User user)
			throws ConfigurationException, InvalidElementException,
			ValidationException {

		if (run.getType() == RunType.Machine) {
			return "\"\"";
		}

		Configuration configuration = Configuration.getInstance();

		String verbosityLevel = getVerboseParameterValue(user);

		String contextualization = "SLIPSTREAM_DIID=" + run.getName() + "#";
		contextualization += "SLIPSTREAM_SERVICEURL=" + configuration.baseUrl
				+ "#";
		contextualization += "SLIPSTREAM_NODENAME=" + getOrchestratorName(run)
				+ "#";
		contextualization += "SLIPSTREAM_CATEGORY="
				+ run.getCategory().toString() + "#";
		contextualization += "SLIPSTREAM_USERNAME=" + user.getName() + "#";
		contextualization += "SLIPSTREAM_COOKIE="
				+ getCookieForEnvironmentVariable(user.getName()) + "#";
		contextualization += "SLIPSTREAM_VERBOSITY_LEVEL=" + verbosityLevel
				+ "#";
		contextualization += "SLIPSTREAM_CLOUD=" + getCloudServiceName() + "#";
		contextualization += "SLIPSTREAM_CONNECTOR_INSTANCE="
				+ getConnectorInstanceName() + "#";

		contextualization += "SLIPSTREAM_BUNDLE_URL="
				+ configuration
						.getRequiredProperty("slipstream.update.clienturl")
				+ "#";

		contextualization += "CLOUDCONNECTOR_BUNDLE_URL="
				+ configuration
						.getRequiredProperty(constructKey("update.clienturl"))
				+ "#";

		contextualization += "CLOUDCONNECTOR_PYTHON_MODULENAME="
				+ CLOUDCONNECTOR_PYTHON_MODULENAME + "#";
		
		contextualization += "SLIPSTREAM_BOOTSTRAP_BIN="
				+ configuration
						.getRequiredProperty("slipstream.update.clientbootstrapurl")
				+ "#";

		contextualization += "SLIPSTREAM_MESSAGING_TYPE="
				+ configuration
						.getRequiredProperty(constructKey(StratusLabUserParametersFactory.MESSAGING_TYPE_PARAMETER_NAME))
				+ "#";
		contextualization += "SLIPSTREAM_MESSAGING_ENDPOINT="
				+ configuration
						.getRequiredProperty(constructKey(StratusLabUserParametersFactory.MESSAGING_ENDPOINT_PARAMETER_NAME))
				+ "#";
		contextualization += "SLIPSTREAM_MESSAGING_QUEUE="
				+ configuration
						.getRequiredProperty(constructKey(StratusLabUserParametersFactory.MESSAGING_QUEUE_PARAMETER_NAME))
				+ "#";

		contextualization += "SLIPSTREAM_REPORT_DIR=" + SLIPSTREAM_REPORT_DIR;

		contextualization += "#" + constructScriptExecCommand();

		return contextualization;
	}

	private String constructScriptExecCommand() throws ConfigurationException {

		Configuration configuration = Configuration.getInstance();

		String bootstrap = "/tmp/slipstream.bootstrap";
		String bootstrapUrl = configuration
				.getRequiredProperty("slipstream.update.clientbootstrapurl");

		return "SCRIPT_EXEC=\"sleep 15; mkdir -p " + SLIPSTREAM_REPORT_DIR
				+ "; wget --no-check-certificate -O " + bootstrap + " "
				+ bootstrapUrl + " > " + SLIPSTREAM_REPORT_DIR
				+ "/orchestrator.slipstream.log 2>&1 && chmod 0755 "
				+ bootstrap + "; " + bootstrap + " slipstream-orchestrator >> "
				+ SLIPSTREAM_REPORT_DIR + "/orchestrator.slipstream.log 2>&1\"";

	}

	private String getExtraDisksCommand(Run run) {
		if (run.getType() != RunType.Machine) {
			return "";
		}
		String disksParams = "";
		for (String diskName : EXTRADISK_NAMES) {
			String extraDiskName = Run.MACHINE_NAME_PREFIX
					+ ImageModule.EXTRADISK_PARAM_PREFIX + diskName;
			String extraDiskValue = "";
			try {
				extraDiskValue = run.getRuntimeParameterValue(extraDiskName);
			} catch (NotFoundException e) {
				continue;
			} catch (AbortException e) {
				continue;
			}
			if (!extraDiskValue.isEmpty()) {
				disksParams += " --" + diskName + "-disk " + extraDiskValue;
			}
		}
		return disksParams;
	}

	@Override
	public Credentials getCredentials(User user) {
		return new StratusLabCredentials(user, getConnectorInstanceName());
	}

	@Override
	public void terminate(Run run, User user) throws SlipStreamException {

		Logger.getLogger(this.getClass().getName()).info(
				"Terminating all instances.");

		String command = "/usr/bin/stratus-kill-instance -u " + getKey(user)
				+ " -p " + getSecret(user) + " --endpoint " + getEndpoint(user);

		for (String id : getCloudNodeInstanceIds(run)) {
			String[] commands = { "sh", "-c", command + " " + id };
			try {
				ProcessUtils.execGetOutput(commands);
			} catch (IOException e) {
			}
		}
	}

	@Override
	public Properties describeInstances(User user) throws SlipStreamException {
		validateCredentials(user);

		String command = "/usr/bin/stratus-describe-instance -u "
				+ getKey(user) + " -p " + getSecret(user) + " --endpoint "
				+ getEndpoint(user);

		String result;
		String[] commands = { "sh", "-c", command };

		try {
			result = ProcessUtils.execGetOutput(commands);
		} catch (IOException e) {
			e.printStackTrace();
			throw (new SlipStreamInternalException(e));
		}

		return parseDescribeInstanceResult(result);
	}

	@Override
	public Map<String, ModuleParameter> getImageParametersTemplate()
			throws ValidationException {
		return new StratusLabImageParametersFactory(getConnectorInstanceName())
				.getParameters();
	}

	@Override
	public Map<String, UserParameter> getUserParametersTemplate()
			throws ValidationException {
		return new StratusLabUserParametersFactory(getConnectorInstanceName())
				.getParameters();
	}

	@Override
	public String constructKey(String key) throws ValidationException {
		return new StratusLabUserParametersFactory(getConnectorInstanceName())
				.constructKey(key);
	}

}
