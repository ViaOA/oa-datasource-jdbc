/*
 * Copyright 1999–2025 ViaOA (info@viaoa.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.viaoa.load;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

import com.viaoa.datasource.OADataSource;
import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.ManyToMany;
import com.viaoa.graph.api.internal.OAGraphInternal;
import com.viaoa.hub.Hub;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.object.OAObject;
import com.viaoa.path.OAPath;
import com.viaoa.runtime.OARuntime;
import com.viaoa.runtime.OAThreadLocalService;
import com.viaoa.runtime.OAThreadService;
import com.viaoa.select.OASelect;

/**
 * Supports asynchronous background preloading of {@link OAObject} data for
 * Hubs or object graphs.
 *
 * <p>Used by OA to warm up caches and avoid latency during first access.
 * Typically invoked through {@link OASelect} or {@link OALoader} to populate
 * references, calculated fields, or dependent collections.</p>
 *
 * <p><b>Key Features</b>:
 * <ul>
 *   <li>Spawns background threads to fetch and cache related data.</li>
 *   <li>Supports prioritized preloading of properties and linked objects.</li>
 *   <li>Integrates with Hub listeners to defer UI updates until data ready.</li>
 * </ul>
 *
 * <p>This class helps OA achieve smooth UX for large object graphs.</p>
 */
public class OAJdbcPreLoader extends OAPreLoader{
	private static Logger LOG = Logger.getLogger(OAJdbcPreLoader.class.getName());

	public OAJdbcPreLoader(Class classFrom, String propPath) {
		super(classFrom, propPath);
	}
	
	/**
	 * Loads many-to-many relationships using metadata and JDBC-based lookup.
	 * Creates or retrieves hubs on each side of the relationship and adds
	 * linked objects accordingly.
	 * <p>
	 * Behavior visible in this method:
	 * <ul>
	 *   <li>Returns when the link is not many-to-many or no JDBC datasource is available.</li>
	 *   <li>Retrieves related objects from the {@link OAObjectCacheDelegate}.</li>
	 *   <li>Creates or retrieves hubs on both sides of the relationship.</li>
	 *   <li>Adds each mapped pair to the appropriate hubs.</li>
	 * </ul>
	 *
	 * @param linkInfo the metadata describing the many-to-many link
	 */
	@Override
	protected void loadMtoM(OALinkInfo linkInfo) {
		if (linkInfo == null || !linkInfo.isMany2Many()) {
			return;
		}
		OADataSource ds = OARuntime.datasource().get(linkInfo.getToClass());
		if (!(ds instanceof OADataSourceJDBC)) {
			return;
		}

		OALinkInfo liA = linkInfo;
		OALinkInfo liB = linkInfo.getReverseLinkInfo();
		if (liB == null) {
			return;
		}

		Class classA = liB.getToClass();
		Class classB = liA.getToClass();

		ArrayList<ManyToMany> alManyToMany = ((OADataSourceJDBC) ds).getManyToMany(linkInfo);
		if (alManyToMany == null) {
			return;
		}

		OAGraphInternal ogA = (OAGraphInternal) OARuntime.graph(classA);
    	OAGraphInternal ogB = (OAGraphInternal) OARuntime.graph(classB);
		
		for (ManyToMany mm : alManyToMany) {
			Object objA = ogA.objectsInternal().callObjectCacheGet(classA, mm.ok1);
			Object objB = ogB.objectsInternal().callObjectCacheGet(classB, mm.ok2);
			if (objA == null || objB == null) {
				continue;
			}

			if (!liA.getPrivateMethod()) {
				Hub hub;
				OAGraphInternal ogX = (OAGraphInternal) OARuntime.graph((OAObject) objA);
				Object objx = ogX.objectsInternal().callObjectPropertyGetProperty((OAObject) objA, liA.getName(), false, true);
				if (objx instanceof Hub) {
					hub = (Hub) objx;
				} else {
					hub = new Hub(classB);
					ogX.objectsInternal().callObjectPropertySetProperty((OAObject) objA, liA.getName(), hub);
				}
				hub.add((OAObject) objB);
			}

			if (!liB.getPrivateMethod()) {
				Hub hub;
				OAGraphInternal ogX = (OAGraphInternal) OARuntime.graph((OAObject) objB);
				Object objx = ogX.objectsInternal().callObjectPropertyGetProperty((OAObject) objB, liB.getName(), false, true);
				if (objx instanceof Hub) {
					hub = (Hub) objx;
				} else {
					hub = new Hub(classA);
					ogX.objectsInternal().callObjectPropertySetProperty((OAObject) objB, liB.getName(), hub);
				}
				hub.add((OAObject) objA);
			}
		}
	}

}
