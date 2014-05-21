package com.sixsq.slipstream.connector.okeanos;

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

import com.sixsq.slipstream.connector.SystemConfigurationParametersFactoryBase;
import com.sixsq.slipstream.connector.UserParametersFactoryBase;
import com.sixsq.slipstream.exceptions.ValidationException;

public class OkeanosSystemConfigurationParametersFactory extends
		SystemConfigurationParametersFactoryBase {

	public OkeanosSystemConfigurationParametersFactory(String connectorInstanceName)
			throws ValidationException {
		super(connectorInstanceName);
	}

    @Override
    protected void putMandatoryEndpoint() throws ValidationException {
        putMandatoryParameter(
            super.constructKey(UserParametersFactoryBase.ENDPOINT_PARAMETER_NAME),
            "Service endpoint for " + getCategory() + " (e.g. https://accounts.okeanos.grnet.gr/identity/v2.0)",
            "https://accounts.okeanos.grnet.gr/identity/v2.0"
        );
    }

	@Override
	protected void initReferenceParameters() throws ValidationException {
        putMandatoryOrchestrationImageId();
		putMandatoryEndpoint();
		
		putMandatoryParameter(
            constructKey(OkeanosUserParametersFactory.ORCHESTRATOR_INSTANCE_TYPE_PARAMETER_NAME),
            "Okeanos flavor for the orchestrator. The actual image should support the desired Flavor"
        );

        putMandatoryParameter(
            constructKey(OkeanosUserParametersFactory.SERVICE_TYPE_PARAMETER_NAME),
            "Type-name of the service which provides the instances functionality",
            "compute"
        );

        putMandatoryParameter(
            constructKey(OkeanosUserParametersFactory.SERVICE_NAME_PARAMETER_NAME),
            "Name of the service which provides the instances functionality",
            "cyclades_compute"
        );

        putMandatoryParameter(
            constructKey(OkeanosUserParametersFactory.SERVICE_REGION_PARAMETER_NAME),
            "Region used by this cloud connector", "default"
        );
	}

}
