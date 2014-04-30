package com.sixsq.slipstream.persistence;

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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.hibernate.LazyInitializationException;
import org.junit.Test;

import com.sixsq.slipstream.exceptions.AbortException;
import com.sixsq.slipstream.exceptions.NotFoundException;
import com.sixsq.slipstream.exceptions.ValidationException;
import com.sixsq.slipstream.statemachine.States;

public class RunTest {

	@Test
	public void loadWithRuntimeParameters() throws ValidationException, NotFoundException, AbortException {
		Module image = new ImageModule();

		Run run = new Run(image, RunType.Run, "test", new User("user"));

		run.assignRuntimeParameter("ss:key", "value", "description");

		run.store();

		run = Run.loadFromUuid(run.getUuid());

		try {
			run.getRuntimeParameterValue("ss:key");
			fail();
		} catch (LazyInitializationException ex) {
		}

		run = Run.loadRunWithRuntimeParameters(run.getUuid());

		assertThat(run.getRuntimeParameterValue("ss:key"), is("value"));
	}

	@Test
	public void oldRuns() throws ValidationException, NotFoundException, AbortException {

		Module image = new ImageModule();

		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.HOUR, -2);
		Date twoHourBack = calendar.getTime();

		List<Run> before = Run.listOldTransient();

		Run done = new Run(image, RunType.Run, "test", new User("user"));
		done.setStart(twoHourBack);
		done.setState(States.Done);
		done.store();

		Run aborting = new Run(image, RunType.Run, "test", new User("user"));
		aborting.setStart(twoHourBack);
		aborting.setState(States.Aborting);
		aborting.store();

		List<Run> transiant = Run.listOldTransient();

		assertThat(transiant.size(), is(before.size() + 1));

		done.remove();
		aborting.remove();
	}
}
