package com.sixsq.slipstream.run;

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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;

import com.sixsq.slipstream.configuration.Configuration;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.ConnectorFactory;
import com.sixsq.slipstream.credentials.Credentials;
import com.sixsq.slipstream.exceptions.ConfigurationException;
import com.sixsq.slipstream.exceptions.ServerExecutionEnginePluginException;
import com.sixsq.slipstream.exceptions.SlipStreamClientException;
import com.sixsq.slipstream.exceptions.SlipStreamException;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.factory.RunFactory;
import com.sixsq.slipstream.persistence.CloudImageIdentifier;
import com.sixsq.slipstream.persistence.DeploymentModule;
import com.sixsq.slipstream.persistence.ImageModule;
import com.sixsq.slipstream.persistence.Module;
import com.sixsq.slipstream.persistence.ModuleCategory;
import com.sixsq.slipstream.persistence.Node;
import com.sixsq.slipstream.persistence.NodeParameter;
import com.sixsq.slipstream.persistence.Run;
import com.sixsq.slipstream.persistence.RunType;
import com.sixsq.slipstream.persistence.RuntimeParameter;
import com.sixsq.slipstream.persistence.ServiceConfiguration;
import com.sixsq.slipstream.persistence.User;
import com.sixsq.slipstream.persistence.Vm;
import com.sixsq.slipstream.resource.BaseResource;
import com.sixsq.slipstream.run.RunView.RunViewList;
import com.sixsq.slipstream.util.ConfigurationUtil;
import com.sixsq.slipstream.util.HtmlUtil;
import com.sixsq.slipstream.util.RequestUtil;
import com.sixsq.slipstream.util.SerializationUtil;

/**
 * Unit test:
 *
 * @see RunListResourceTest.class
 *
 */
public class RunListResource extends BaseResource {

	public static final String REFQNAME = "refqname";
	public static final String IGNORE_ABORT_QUERY = "ignoreabort";
	String refqname = null;

	@Get("txt")
	public Representation toTxt() {
		RunViewList runViewList = fetchListView();
		String result = SerializationUtil.toXmlString(runViewList);
		return new StringRepresentation(result);
	}

	@Get("xml")
	public Representation toXml() {

		RunViewList runViewList = fetchListView();
		String result = SerializationUtil.toXmlString(runViewList);
		return new StringRepresentation(result, MediaType.APPLICATION_XML);
	}

	@Get("html")
	public Representation toHtml() {

		RunViewList runViewList = fetchListView();

		return new StringRepresentation(HtmlUtil.toHtml(runViewList,
				getPageRepresentation(), getTransformationType(), getUser()),
				MediaType.TEXT_HTML);
	}

	private RunViewList fetchListView() {

		Reference resourceRef = getRequest().getResourceRef();
		Form form = resourceRef.getQueryAsForm();
		String query = form.getFirstValue("query");

		RunViewList list = null;
		try {
			list = fetchListView(query, getUser());
		} catch (ConfigurationException e) {
			throwConfigurationException(e);
		} catch (ValidationException e) {
			throwClientValidationError(e.getMessage());
		}
		return list;
	}

	static RunViewList fetchListView(String query, User user)
			throws ConfigurationException, ValidationException {
		List<RunView> list;

		if (user.isSuper()) {
			list = (query != null) ? Run.viewList(query, user) : Run
					.viewListAll();
		} else {
			list = (query != null) ? Run.viewList(query, user) : Run
					.viewList(user);
		}
		return new RunViewList(list);
	}

	/**
	 * We need to merge data from different sources: - the form (entity) - the
	 * default module
	 *
	 * In the case of orchestrator + VM(s) (i.e. deployment and build) we also
	 * need to merge: - the default from each node
	 *
	 * The service cloud is the part that causes most trouble, since it can be
	 * defined at all levels.
	 */
	@Post("form|txt")
	public void createRun(Representation entity) throws ResourceException,
			FileNotFoundException, IOException, SQLException,
			ClassNotFoundException, SlipStreamException {

		Form form = new Form(entity);
		setReference(form);

		Run run;
		try {
			Module module = loadReferenceModule();

			authorizePost(module);
			
			updateReference(module);

			overrideModule(form, module);

			module.validate();

			User user = getUser();
			user = User.loadByName(user.getName()); // ensure user is loaded from database

			String defaultCloudService = getDefaultCloudService(form, user);
			
			run = RunFactory.getRun(module, parseType(form), defaultCloudService, user);

			run = addCredentials(run);

			if (Configuration.isQuotaEnabled()) {
				Quota.validate(user, run.getCloudServiceUsage(), Vm.usage(user.getName()));
			}

			createRepositoryResource(run);

			run.store();

			launch(run);

		} catch (SlipStreamClientException ex) {
			throw (new ResourceException(Status.CLIENT_ERROR_CONFLICT,
					ex.getMessage()));
		}

		String location = "/" + Run.RESOURCE_URI_PREFIX + run.getName();
		String absolutePath = RequestUtil.constructAbsolutePath(location);

		getResponse().setStatus(Status.SUCCESS_CREATED);
		getResponse().setLocationRef(absolutePath);
	}

	private void authorizePost(Module module) {
		if(!module.getAuthz().canPost(getUser())) {
			throwClientForbiddenError("User does not have the rights to execute this module");
		}
	}

	/**
	 * If the form contains a cloudservice parameter, use that
	 * otherwise, use the user default. Builds and Simple Run
	 * will contain a cloud service, where for deployment this
	 * is defined per node, which means more processing by the
	 * factory.
	 */
	private String getDefaultCloudService(Form form, User user) {
		return form.getFirstValue("parameter--cloudservice", user.getDefaultCloudService());
	}

	private String getDefaultCloudService() {
		String cloudServiceName = getUser().getDefaultCloudService();
		if (cloudServiceName == null || "".equals(cloudServiceName)) {
			throw (new ResourceException(
					Status.CLIENT_ERROR_CONFLICT,
					ConnectorFactory
							.incompleteCloudConfigurationErrorMessage(getUser())));
		}
		return cloudServiceName;
	}

	private void setReference(Form form) {
		refqname = form.getFirstValue(REFQNAME);

		if (refqname == null) {
			throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST,
					"Missing refqname in POST");
		}
		refqname = refqname.trim();
	}

	private void overrideModule(Form form, Module module)
			throws ValidationException {
		if (module.getCategory() == ModuleCategory.Deployment) {
			overrideNodes(form, (DeploymentModule) module);
		}
		if (module.getCategory() == ModuleCategory.Image) {
			overrideImage(form, (ImageModule) module);
		}
	}

	private void overrideNodes(Form form, DeploymentModule deployment)
			throws ValidationException {

		Map<String, List<NodeParameter>> parametersPerNode = parseNodeNameOverride(form);

		String defaultCloudService = getDefaultCloudService();

		for (Node node : deployment.getNodes().values()) {
			if (CloudImageIdentifier.DEFAULT_CLOUD_SERVICE.equals(node
					.getCloudService()))
				node.setCloudService(defaultCloudService);
		}

		for (Map.Entry<String, List<NodeParameter>> entry : parametersPerNode.entrySet()) {
            String nodename = entry.getKey();
			if (!deployment.getNodes().containsKey(nodename)) {
				throw new ValidationException("Unknown node: " + nodename);
			}

			Node node = deployment.getNodes().get(nodename);

			for (NodeParameter parameter : entry.getValue()) {
				if (parameter.getName().equals(
						RuntimeParameter.MULTIPLICITY_PARAMETER_NAME)) {
					node.setMultiplicity(parameter.getValue());
					continue;
				}
				if (parameter.getName().equals(
						RuntimeParameter.CLOUD_SERVICE_NAME)) {
					String cloudService = (CloudImageIdentifier.DEFAULT_CLOUD_SERVICE
							.equals(parameter.getValue()) ? defaultCloudService
							: parameter.getValue());
					node.setCloudService(cloudService);
					continue;
				}
				if (!node.getParameters().containsKey(parameter.getName())) {
					throw new ValidationException("Unknown parameter: "
							+ parameter.getName() + " in node: " + nodename);
				}
				node.getParameters().get(parameter.getName())
						.setValue("'" + parameter.getValue() + "'");
			}

		}
	}

	public static Map<String, List<NodeParameter>> parseNodeNameOverride(
			Form form) throws ValidationException {
		Map<String, List<NodeParameter>> parametersPerNode = new HashMap<String, List<NodeParameter>>();
		for (Entry<String, String> entry : form.getValuesMap().entrySet()) {
			// parameter--node--[nodename]--[paramname]
			String[] parts = entry.getKey().split("--");
			if (RunListResource.REFQNAME.equals(entry.getKey())) {
				continue;
			}
			if (parts.length != 4) {
				throw new ValidationException("Invalid key format: "
						+ entry.getKey());
			}
			String nodename = parts[2];
			String parameterName = parts[3];
			String value = entry.getValue();
			if (!parametersPerNode.containsKey(nodename)) {
				parametersPerNode.put(nodename, new ArrayList<NodeParameter>());
			}
			NodeParameter parameter = new NodeParameter(parameterName);
			parameter.setUnsafeValue(value);
			parametersPerNode.get(nodename).add(parameter);
		}
		return parametersPerNode;
	}

	private void overrideImage(Form form, ImageModule image)
			throws ValidationException {
		// TODO...
		// Map<String, List<NodeParameter>> parametersPerNode = NodeParameter
		// .parseNodeNameOverride(form);
		// for (NodeParameter parameter : parametersPerNode.get(nodename)) {
		// if (parameter.getName().equals(
		// RuntimeParameter.CLOUD_SERVICE_NAME)) {
		// node.setCloudService(parameter.getValue());
		// continue;
		// }
	}

	private RunType parseType(Form form) {
		String type = form.getFirstValue("type", true,
				RunType.Orchestration.toString());
		try {
			return RunType.valueOf(type);
		} catch (IllegalArgumentException e) {
			throw (new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST,
					"Unknown run type: " + type));
		}
	}

	private Run launch(Run run) throws SlipStreamException {
		slipstream.async.Launcher.launch(run, getUser());
		return run;
	}

	private Run addCredentials(Run run) throws ConfigurationException,
			ServerExecutionEnginePluginException, ValidationException {

		Credentials credentials = loadCredentialsObject();
		run.setCredentials(credentials);

		return run;
	}

	private Credentials loadCredentialsObject() throws ConfigurationException,
			ValidationException {

		Connector connector = ConnectorFactory.getCurrentConnector(getUser());
		return connector.getCredentials(getUser());
	}

	private void createRepositoryResource(Run run)
			throws ConfigurationException {
		String repositoryLocation;
		repositoryLocation = ConfigurationUtil
				.getConfigurationFromRequest(getRequest())
				.getRequiredProperty(
						ServiceConfiguration.RequiredParameters.SLIPSTREAM_REPORTS_LOCATION
								.getName());

		String absRepositoryLocation = repositoryLocation + "/" + run.getName();

		boolean createdOk = new File(absRepositoryLocation).mkdirs();
		// Create the repository structure
		if (!createdOk) {
			throw new ResourceException(Status.SERVER_ERROR_INTERNAL,
					"Error creating repository structure");
		}
	}

	private Module loadReferenceModule() throws ValidationException {
		Module module = Module.load(refqname);
		if (module == null) {
			throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND,
					"Coudn't find reference module: " + refqname);
		}
		return module;
	}

	private void updateReference(Module module) {
		refqname = module.getName();
	}

	@Override
	protected String getPageRepresentation() {
		return "runs";
	}

}
