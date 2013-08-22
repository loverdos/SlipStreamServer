package com.sixsq.slipstream.util;

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

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.representation.Representation;
import org.restlet.resource.ServerResource;

import com.sixsq.slipstream.configuration.Configuration;
import com.sixsq.slipstream.connector.Connector;
import com.sixsq.slipstream.connector.ConnectorFactory;
import com.sixsq.slipstream.connector.SystemConfigurationParametersFactoryBase;
import com.sixsq.slipstream.connector.local.LocalConnector;
import com.sixsq.slipstream.exceptions.ConfigurationException;
import com.sixsq.slipstream.exceptions.SlipStreamRuntimeException;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.persistence.Authz;
import com.sixsq.slipstream.persistence.Module;
import com.sixsq.slipstream.persistence.ProjectModule;
import com.sixsq.slipstream.persistence.ServiceConfiguration;
import com.sixsq.slipstream.persistence.ServiceConfiguration.RequiredParameters;
import com.sixsq.slipstream.persistence.ServiceConfigurationParameter;
import com.sixsq.slipstream.persistence.User;

public class ResourceTestBase {

	protected static final String PASSWORD = "password";

	// Need to set cloudServiceName before the status user is
	// created, since the createUser method uses it
	protected static String cloudServiceName = new LocalConnector()
			.getCloudServiceName();

	protected static User user = createUser("test", PASSWORD);

	public static User createUser(String name) {
		return ResourceTestBase.createUser(name, null);
	}

	public static User createUser(String name, String password) {
		User user = (User) User.loadByName(name);
		if (user != null) {
			user.remove();
		}
		try {
			user = new User(name);
		} catch (ValidationException e) {
			e.printStackTrace();
		}
		user.setPassword(password);

		try {
			user.setDefaultCloudServiceName(cloudServiceName);
		} catch (ValidationException e) {
			throw (new SlipStreamRuntimeException(e));
		}

		return user;
	}

	public static User storeUser(User user) {
		return user.store();
	}

	public static void resetAndLoadConnector(
			Class<? extends Connector> connectorClass)
			throws InstantiationException, IllegalAccessException,
			InvocationTargetException, NoSuchMethodException,
			ClassNotFoundException {

		// Instantiate the configuration force loading from disk
		// This is required otherwise the first loading from
		// disk will reset the connectors
		Configuration.getInstance();

		Map<String, Connector> connectors = new HashMap<String, Connector>();
		Connector connector = ConnectorFactory
				.instantiateConnectorFromName(connectorClass.getName());
		connectors.put(connector.getCloudServiceName(), connector);
		ConnectorFactory.setConnectors(connectors);
	}

	public Request createRequest(Map<String, Object> attributes, Method method)
			throws ConfigurationException {
		return createRequest(attributes, method, null);
	}

	public Request createRequest(Map<String, Object> attributes, Method method,
			Representation entity) throws ConfigurationException {
		return createRequest(attributes, method, entity, "/test/request");
	}

	public Request createRequest(Map<String, Object> attributes, Method method,
			Representation entity, String targetUrl)
			throws ConfigurationException {
		Request request = new Request(method, "http://something.org"
				+ targetUrl);
		request.setRootRef(new Reference("http://something.org"));
		request.setEntity(entity);
		request.setAttributes(attributes);

		RequestUtil.addConfigurationToRequest(request);

		return request;
	}

	protected Request createGetRequest(Map<String, Object> attributes)
			throws ConfigurationException {
		Method method = Method.GET;
		return createRequest(attributes, method);
	}

	protected Request createPutRequest(Map<String, Object> attributes,
			Representation entity) throws ConfigurationException {
		return createRequest(attributes, Method.PUT, entity);
	}

	protected Request createPutRequest(Map<String, Object> attributes,
			Representation entity, String targetUrl)
			throws ConfigurationException {
		return createRequest(attributes, Method.PUT, entity, targetUrl);
	}

	protected Request createDeleteRequest(Map<String, Object> attributes)
			throws ConfigurationException {
		Method method = Method.DELETE;
		return createRequest(attributes, method);
	}

	protected Request createDeleteRequest(Map<String, Object> attributes,
			User user) throws ConfigurationException {
		Method method = Method.DELETE;
		Request request = createRequest(attributes, method);
		addUserToRequest(user.getName(), request);
		return request;
	}

	protected Request createPostRequest(Map<String, Object> attributes,
			Representation entity) throws ConfigurationException {
		Method method = Method.POST;
		return createRequest(attributes, method, entity);
	}

	protected Response executeRequest(Request request, ServerResource resource) {

		Response response = new Response(request);

		resource.init(null, request, response);
		if (response.getStatus().isSuccess()) {
			resource.handle();
		}

		return resource.getResponse();
	}

	protected Map<String, Object> createAttributes(String name, String value) {
		Map<String, Object> attributes = new HashMap<String, Object>();
		attributes.put(name, value);
		return attributes;
	}

	protected Request createGetRequest(String module, User user)
			throws ConfigurationException {
		Request request = createGetRequest(createModuleAttributes(module));
		addUserToRequest(user.getName(), request);
		return request;
	}

	protected Module createAndStoreProject(String projectName)
			throws ValidationException {
		Module project = new ProjectModule(projectName);
		project.setAuthz(new Authz(user.getName(), project));
		project.store();
		return project;
	}

	protected Request createPutRequest(String name, Representation entity,
			User user) throws ConfigurationException {
		Request request = createPutRequest(createModuleAttributes(name), entity);
		addUserToRequest(user.getName(), request);
		return request;
	}

	protected Map<String, Object> createModuleAttributes(String module) {
		return createAttributes("module", module);
	}

	protected void addUserToRequest(User user, Request request) {
		addUserToRequest(user.getName(), request);
	}

	protected void addUserToRequest(String username, Request request) {
		request.getClientInfo()
				.setUser(new org.restlet.security.User(username));
	}

	public static void setCloudConnector(String connectorClassName)
			throws ConfigurationException {
		Configuration configuration = Configuration.getInstance();

		ServiceConfiguration sc = configuration.getParameters();
		sc.setParameter(new ServiceConfigurationParameter(
				RequiredParameters.CLOUD_CONNECTOR_CLASS.getValue(),
				connectorClassName));
		sc.store();
		ConnectorFactory.resetConnectors();
	}

	protected static void updateServiceConfigurationParameters(
			SystemConfigurationParametersFactoryBase connectorSystemConfigFactory)
			throws ValidationException {
		ServiceConfiguration sc = Configuration.getInstance().getParameters();
		sc.setParameters(connectorSystemConfigFactory.getParameters());
		sc.store();
	}
}
