package com.sixsq.slipstream.factory;

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

import java.util.HashSet;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.sixsq.slipstream.connector.ExecutionControlUserParametersFactory;
import com.sixsq.slipstream.exceptions.NotFoundException;
import com.sixsq.slipstream.exceptions.SlipStreamClientException;
import com.sixsq.slipstream.exceptions.SlipStreamInternalException;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.persistence.DeploymentModule;
import com.sixsq.slipstream.persistence.ImageModule;
import com.sixsq.slipstream.persistence.Module;
import com.sixsq.slipstream.persistence.ModuleCategory;
import com.sixsq.slipstream.persistence.ModuleParameter;
import com.sixsq.slipstream.persistence.Node;
import com.sixsq.slipstream.persistence.NodeParameter;
import com.sixsq.slipstream.persistence.Parameter;
import com.sixsq.slipstream.persistence.Run;
import com.sixsq.slipstream.persistence.RunParameter;
import com.sixsq.slipstream.persistence.RunType;
import com.sixsq.slipstream.persistence.RuntimeParameter;
import com.sixsq.slipstream.persistence.User;
import com.sixsq.slipstream.persistence.UserParameter;

public class DeploymentFactory extends RunFactory {

	@Override
	protected Run constructRun(Module module, String cloudService, User user)
			throws ValidationException {
		Run run = new Run(module, RunType.Orchestration, cloudService, user);
		
		run = addOnSuccessRunForeverToParameters(run, user);
		run = addOnErrorRunForeverToParameters(run, user);
		
		return run;
	}

	private Run addOnSuccessRunForeverToParameters(Run run, User user) throws ValidationException {
		String key = Parameter.constructKey(ExecutionControlUserParametersFactory.CATEGORY, 
				UserParameter.KEY_ON_SUCCESS_RUN_FOREVER);
		
		UserParameter up = user.getParameter(key);
		if (up != null) {
			run.setParameter(new RunParameter(up.getName(), up.getValue(), up.getDescription()));
		}
		
		return run;
	}
	
	private Run addOnErrorRunForeverToParameters(Run run, User user) throws ValidationException {
		String key = Parameter.constructKey(ExecutionControlUserParametersFactory.CATEGORY, 
				UserParameter.KEY_ON_ERROR_RUN_FOREVER);
		
		UserParameter up = user.getParameter(key);
		if (up != null) {
			run.setParameter(new RunParameter(up.getName(), up.getValue(), up.getDescription()));
		}
		
		return run;
	}
	
	@Override
	protected void initialize(Module module, Run run, String cloudService)
			throws ValidationException, NotFoundException {

		super.initialize(module, run, cloudService);

		initializeVmRuntimeParameters(run);
		initializeOrchestrtorRuntimeParameters(run);
		initOrchestratorsNodeNames(run);
		initNodeRuntimeParameters(run);
	}

	@Override
	protected void validateRun(Run run, User user, String cloudService)
			throws SlipStreamClientException {
		
		super.validateRun(run, user, cloudService);
		
		checkIsDeploymentModule(run);

		checkAllImagesHaveReferenceOrImageId(run);
	}

	private void checkIsDeploymentModule(Run run) throws ValidationException {
		if (!(run.getModule() instanceof DeploymentModule)) {
			throw new ValidationException(
					"Only deployment modules can be deployed");
		}
	}

	private void checkAllImagesHaveReferenceOrImageId(Run run)
			throws ValidationException {

		DeploymentModule deployment = (DeploymentModule) run.getModule();

		for (Node node : deployment.getNodes().values()) {
			String cloudServiceName = getEffectiveCloudServiceName(
					node.getCloudService(), run);
			ImageModule image = node.getImage();
			if (image == null) {
				throw new ValidationException("Unknown image: "
						+ node.getImageUri());
			}

			try {
				checkImageHasReferenceOrImageId(image, cloudServiceName);
			} catch (ValidationException ex) {
				throw new ValidationException("Node " + node.getName()
						+ " refers to image " + ex.getMessage());
			}
		}
	}

	private void checkImageHasReferenceOrImageId(ImageModule image,
			String cloudServiceName) throws ValidationException {

		if (!"".equals(image.getCloudImageId(cloudServiceName))) {
			return;
		}
		boolean mustHaveImageId = image.isBase() || !image.isVirtual();
		if (mustHaveImageId
				&& "".equals(image.getCloudImageId(cloudServiceName))) {
			throw new ValidationException(image.getName()
					+ " missing an image id for cloud: " + cloudServiceName
					+ ". Did you build it?");
		} else if ("".equals(image.getModuleReference())) {
			throw new ValidationException(image.getName()
					+ " missing a machine image reference");
		} else {
			String referenceUri = image.getModuleReference();
			ImageModule reference = (ImageModule) ImageModule
					.load(referenceUri);
			if (reference == null) {
				throw new ValidationException("Image " + image.getName()
						+ " refers to an unknown image "
						+ image.getModuleReference());
			}
			checkImageHasReferenceOrImageId(reference, cloudServiceName);
		}
	}

	private void initNodeRuntimeParameters(Run run) throws ValidationException,
			NotFoundException {

		DeploymentModule deployment = (DeploymentModule) run.getModule();

		for (Node node : deployment.getNodes().values()) {
			run = initMachineState(node, run);
			Module image = node.getImage();
			for (ModuleParameter param : image.getParameterList()) {
				String initialValue = extractInitialValue(param, node);
				run.createRuntimeParameter(node, param.getName(), initialValue,
						param.getDescription(), param.getType());
			}
		}

		// mapping
		for (Node node : deployment.getNodes().values()) {
			int multiplicity = node.getMultiplicity();
			for (NodeParameter param : node.getParameterMappings().values()) {
				for (int i = 1; i <= multiplicity; i++) {
					if (!param.isStringValue()) {
						addParameterMapping(run, param, i);
					}
				}
			}
		}

	}

	private static void addParameterMapping(Run run, NodeParameter param, int i) {
		String name = insertMultiplicityIndexInParameterName(param.getValue(),
				1);
		RuntimeParameter input = run.getRuntimeParameters().get(name);
		input.setMapsOthers(true);
		input.addMappedRuntimeParameterName(insertMultiplicityIndexInParameterName(
				param.getContainer().getName()
						+ RuntimeParameter.NODE_PROPERTY_SEPARATOR
						+ param.getName(), i));
		if (input.isSet()) {
			input.setValue(input.getValue());
		}
		run.getRuntimeParameters().put(input.getName(), input);
	}

	public static String insertMultiplicityIndexInParameterName(String name,
			int index) {
		String[] parts = name.split(RuntimeParameter.NODE_PROPERTY_SEPARATOR);
		return parts[0] + RuntimeParameter.NODE_MULTIPLICITY_INDEX_SEPARATOR
				+ index + RuntimeParameter.NODE_PROPERTY_SEPARATOR + parts[1];
	}

	private Run initMachineState(Node node, Run run)
			throws ValidationException, NotFoundException {

		int multiplicity = node.getMultiplicity();
		String cloudServiceName = getEffectiveCloudServiceName(
				node.getCloudService(), run);

		for (int i = 1; i <= multiplicity; i++) {

			String nodename = constructNodeName(node.getName(), i);
			assignRuntimeParameters(run, nodename);
			run.assignRuntimeParameter(
					constructParamName(nodename,
							RuntimeParameter.MULTIPLICITY_PARAMETER_NAME),
					String.valueOf(multiplicity),
					"Multiplicity value for this node");
			String paramname = RuntimeParameter.NODE_NAME;
			run.assignRuntimeParameter(constructParamName(nodename, paramname),
					node.getName(), "Nodename");
			run.assignRuntimeParameter(
					constructParamName(nodename, RuntimeParameter.NODE_INDEX),
					String.valueOf(i), "Node index");

			run.assignRuntimeParameter(
					constructParamName(nodename,
							RuntimeParameter.CLOUD_SERVICE_NAME),
					cloudServiceName,
					RuntimeParameter.CLOUD_SERVICE_DESCRIPTION);

			run.addNodeName(nodename, cloudServiceName);
		}

		run.addGroup(node.getName(), cloudServiceName);

		return run;
	}

	private static String constructNodeName(String groupname, int index) {
		return RuntimeParameter.constructNodeName(groupname, index);
	}

	private static String constructParamName(String nodename, String paramname) {
		return RuntimeParameter.constructParamName(nodename, paramname);
	}

	private static String extractInitialValue(ModuleParameter parameter,
			Node node) {

		String initialNodeValue = extractInitialValue(node
				.getParameter(parameter.getName()));
		String defaultModuleParameter = parameter.getValue();

		return initialNodeValue == null ? defaultModuleParameter
				: initialNodeValue;
	}

	private static String extractInitialValue(NodeParameter parameter) {

		if (parameter == null) {
			return null;
		}

		String value = "";
		if (parameter.isStringValue()) {
			int length = parameter.getValue().length();
			value = parameter.getValue().substring(1, length - 1);
		}

		return value;
	}

	@Override
	public Module overloadModule(Run run, User user) throws ValidationException {

		DeploymentModule deployment = DeploymentModule.populateFromRun(run,
				loadModule(run), user);

		for (Node node : deployment.getNodes().values()) {
			RunFactory.resolveImageIdIfAppropriate(node.getImage(), user);
		}

		return deployment;
	}

	protected static void initializeVmRuntimeParameters(Run run)
			throws ValidationException {

		DeploymentModule deployment = (DeploymentModule) run.getModule();
		for (Node node : deployment.getNodes().values()) {

			String nodeRuntimeParameterKeyName = run
					.nodeRuntimeParameterKeyName(node,
							RuntimeParameter.MULTIPLICITY_PARAMETER_NAME);
			run.setParameter(new RunParameter(nodeRuntimeParameterKeyName,
					String.valueOf(node.getMultiplicity()),
					RuntimeParameter.MULTIPLICITY_PARAMETER_DESCRIPTION));

			nodeRuntimeParameterKeyName = run.nodeRuntimeParameterKeyName(node,
					RuntimeParameter.CLOUD_SERVICE_NAME);
			run.setParameter(new RunParameter(nodeRuntimeParameterKeyName,
					String.valueOf(node.getCloudService()),
					RuntimeParameter.CLOUD_SERVICE_DESCRIPTION));

			nodeRuntimeParameterKeyName = run.nodeRuntimeParameterKeyName(node,
					RuntimeParameter.TAGS_KEY);
			run.setParameter(new RunParameter(nodeRuntimeParameterKeyName, "",
					RuntimeParameter.GLOBAL_TAGS_DESCRIPTION));
		}
	}

	public static Map<String, Node> getNodes(Run run) throws ValidationException {
		Module module = run.getModule(false);
		if (module == null) {
			module = new DeploymentFactory().overloadModule(run,
					User.loadByName(run.getUser()));
		}

		if (module.getCategory() != ModuleCategory.Deployment) {
			throw new SlipStreamInternalException(
					"getNodes can only be used with a Deployment module");
		}

		return ((DeploymentModule) module).getNodes();
	}

	public static HashSet<String> getCloudServicesList(Run run)
			throws ValidationException {
		HashSet<String> cloudServicesList = new HashSet<String>();
		for (Node n : getNodes(run).values()) {
			String cloudServiceName = n.getCloudService();
			cloudServicesList.add(getEffectiveCloudServiceName(
					cloudServiceName, run));
		}
		return cloudServicesList;
	}

	@Override
	protected void initCloudServices(Run run) throws ValidationException {
		run.setCloudServiceNames(StringUtils.join(getCloudServicesList(run),
				","));
	}

}
