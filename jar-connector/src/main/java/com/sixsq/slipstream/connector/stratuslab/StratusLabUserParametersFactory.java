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

import com.sixsq.slipstream.connector.UserParametersFactoryBase;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.persistence.ParameterType;

public class StratusLabUserParametersFactory extends UserParametersFactoryBase {

	public static final String MARKETPLACE_ENDPOINT_PARAMETER_NAME = "marketplace.endpoint";
	public static final String MESSAGING_TYPE_PARAMETER_NAME = "messaging.type";
	public static final String MESSAGING_ENDPOINT_PARAMETER_NAME = "messaging.endpoint";
	public static final String MESSAGING_QUEUE_PARAMETER_NAME = "messaging.queue";


	public StratusLabUserParametersFactory(String connectorInstanceName) throws ValidationException {
		super(connectorInstanceName);
	}

	@Override
	protected void initReferenceParameters() throws ValidationException {

		putMandatoryParameter(KEY_PARAMETER_NAME, "StratusLab account username",
				ParameterType.RestrictedString, 10);

		putMandatoryPasswordParameter(SECRET_PARAMETER_NAME,
				"StratusLab account password", 20);

	}

}
