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

import java.util.ArrayList;
import java.util.List;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

@Root(name = "vm")
public class VmView {

	@Attribute
	public final String runUuid;

	@Attribute
	public final String instanceId;

	@Attribute(required = false)
	public final String status;

	@Attribute
	public final String cloudServiceName;

	public VmView(String instanceId, String status, String runUuid,
			String cloudServiceName) {
		this.runUuid = runUuid;
		this.status = status;
		this.instanceId = instanceId;
		this.cloudServiceName = cloudServiceName;
	}

	@Root(name = "vms")
	public static class VmViewList {

		@ElementList(inline = true, required = false)
		private List<VmView> list = new ArrayList<VmView>();

		public VmViewList() {
		}

		public VmViewList(List<VmView> list) {
			this.list = list;
		}

		public List<VmView> getList() {
			return list;
		}
	}

}
