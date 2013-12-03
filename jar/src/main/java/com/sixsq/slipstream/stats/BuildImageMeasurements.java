package com.sixsq.slipstream.stats;

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

import java.util.List;

import org.simpleframework.xml.Root;

import com.sixsq.slipstream.exceptions.AbortException;
import com.sixsq.slipstream.exceptions.NotFoundException;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.persistence.Run;
import com.sixsq.slipstream.persistence.RunType;

/**
 * Unit test:
 * 
 * @see MeasurementTest
 * 
 */
@Root(name = "measurements")
@SuppressWarnings("serial")
public class BuildImageMeasurements extends Measurements {

	@Override
	protected List<Measurement> populateSingle(Run run)
			throws ValidationException, NotFoundException, AbortException {

		// might be 'default'
		String effectiveCloud = run.getEffectiveCloudServiceName(run
				.getCloudService());

		Measurement ms;

		// Orchestrator
		String imagename = Run.ORCHESTRATOR_NAME;
		String nodename = Run.constructOrchestratorName(effectiveCloud);
		// for builds the orchestrator node is not decorated with the cloud
		// (there's only one)
		ms = fill(run, nodename, imagename, "");
		ms.setType(RunType.Machine);

		// Machine
		imagename = run.getModule().getName();
		nodename = Run.MACHINE_NAME;

		ms = fill(run, nodename, imagename, effectiveCloud);
		ms.setType(RunType.Machine);

		return getMeasurments();
	}

	protected RunType getType() {
		return RunType.Machine;
	}
}