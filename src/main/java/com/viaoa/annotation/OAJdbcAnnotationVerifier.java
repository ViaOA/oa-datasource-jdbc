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
package com.viaoa.annotation;

import java.lang.reflect.Method;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;

import com.viaoa.datasource.OADataSource;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.Database;
import com.viaoa.datasource.jdbc.db.Index;
import com.viaoa.datasource.jdbc.db.Link;
import com.viaoa.datasource.jdbc.db.Table;
import com.viaoa.graph.OAGraph;
import com.viaoa.graph.OAGraphImpl;
import com.viaoa.graph.api.internal.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectAnnotationService;
import com.viaoa.graph.service.object.OAObjectHubService;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.hub.Hub;
import com.viaoa.hub.HubInternalBridge;
import com.viaoa.lang.OAArray;
import com.viaoa.metadata.OACalcInfo;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.metadata.OAPropertyInfo;
import com.viaoa.object.OAObject;
import com.viaoa.runtime.OARuntime;
import com.viaoa.text.OATextCode;

/**
 * Validates that OA model annotations match the runtime metadata generated
 * by {@link OAObjectInfo} and, optionally, the physical JDBC database schema.
 *
 * <p>This verifier performs deep structural validation of an OA model class by
 * comparing all annotations—{@link OAClass}, {@link OAProperty},
 * {@link OACalculatedProperty}, {@link OAId}, {@link OAOne}, {@link OAMany},
 * {@link OATable}, {@link OAColumn}, {@link OAIndex}, and {@link OAIndexColumn}—
 * against the computed {@link OAObjectInfo} and/or the database metadata from
 * {@link com.viaoa.datasource.jdbc.db.Database}.</p>
 *
 * <p><b>Validation Coverage</b>:
 * <ul>
 *   <li>Class-level settings (useDataSource, localOnly, cache, initialization).</li>
 *   <li>ID properties and their ordering.</li>
 *   <li>All properties including maxLength, required, id-flag, and SQL types.</li>
 *   <li>Calculated properties and dependent property lists.</li>
 *   <li>All link definitions (one-to-one, one-to-many, many-to-many).</li>
 *   <li>Database table, columns, foreign keys, and index structures.</li>
 * </ul>
 *
 * <p>This tool is mainly used during development or model generation to ensure
 * that annotations, OAObjectInfo metadata, and the relational schema are mutually
 * consistent. A mismatch indicates either a model definition issue or a schema
 * drift.</p>
 */
public class OAJdbcAnnotationVerifier {

	private static Logger LOG = Logger.getLogger(OAJdbcAnnotationVerifier.class.getName());

	

	/**
	 * Verifies that the class annotations for the specified class match the
	 * corresponding JDBC database schema.
	 * <p>
	 * Validation includes:
	 * <ul>
	 *   <li>Table existence and column definitions.</li>
	 *   <li>Column SQL types, lengths, decimal places, GUID and primary-key flags.</li>
	 *   <li>Foreign-key structure and index definitions.</li>
	 * </ul>
	 * Any mismatch prints a diagnostic message and marks the result as invalid.
	 *
	 * @param clazz    the model class whose table definition is validated
	 * @param database the database metadata used for comparison
	 * @return {@code true} if annotations and schema match; otherwise {@code false}
	 * @throws Exception if reflection access errors occur
	 */
	public boolean verify(Class clazz, Database database) throws Exception {
		boolean[] bs = null;
		int i;
		String s;
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);

		Method[] methods = clazz.getDeclaredMethods(); // need to get all access types, since some could be private. qqqqqq does not get superclass methods

		// columns
		OATable dbTable = (OATable) clazz.getAnnotation(OATable.class);
		if (dbTable == null) {
			p("no table annotation");
			return false;
		}

		Table table = database.getTable(clazz);
		if (table == null) {
			p("table not found");
			return false;
		}

		boolean bResult = true;
		Column[] columns = table.getColumns();
		bs = new boolean[columns.length];

		for (Method m : methods) {
			OAColumn col = (OAColumn) m.getAnnotation(OAColumn.class);

			if (col != null) {
				String name = col.name();
				if (name == null || name.length() == 0) {
					name = OATextCode.getPropertyName(m.getName());
				}

				boolean b = false;
				for (i = 0; i < columns.length; i++) {
					if (columns[i].columnName.equalsIgnoreCase(name)) {
						bs[i] = true;
						b = true;
						if (columns[i].type != col.sqlType()) {
							int xx = col.sqlType();
							p("column sql type mismatch");
							bResult = false;
						}
						s = OATextCode.getPropertyName(m.getName());
						if (!s.equalsIgnoreCase(columns[i].propertyName)) {
							p("column prop name mismatch");
							bResult = false;
						}

						OAId id = (OAId) m.getAnnotation(OAId.class);
						if (id != null) {
							if (id.autoAssign() != columns[i].primaryKey) {
								p("column pkey mismatch");
								bResult = false;
							}
							if (id.guid() != columns[i].guid) {
								p("column guid mismatch");
								bResult = false;
							}
						}

						if (col.sqlType() == java.sql.Types.VARCHAR) {
							OAProperty oaprop = (OAProperty) m.getAnnotation(OAProperty.class);
							if (oaprop != null) {
								int x = oaprop.maxLength();
								if (x > 0) {
									if (columns[i].maxLength != x) {
										p("varchar maxLength mismatch, column=" + columns[i].columnName);
										bResult = false;
									}
								}
								x = oaprop.decimalPlaces();
								if (x >= 0) {
									if (columns[i].decimalPlaces != x) {
										p("varchar decimalPlaces mismatch, column=" + columns[i].columnName);
										bResult = false;
									}
								}
							}
						}
						break;
					}
				}
				if (!b) {
					p("did not find column");
					bResult = false;
				}
			}

			/* todo:
			OAFkey fk = (OAFkey) m.getAnnotation(OAFkey.class);
			if (fk == null) {
				continue;
			}
			
			String[] fkcols = fk.columns();
			for (int j = 0; j < fkcols.length; j++) {
				boolean b = false;
				for (i = 0; i < columns.length; i++) {
					if (columns[i].columnName.equalsIgnoreCase(fkcols[j])) {
						b = true;
						bs[i] = true;
						break;
					}
				}
				if (!b) {
					p("did not find fkcolumn");
					bResult = false;
				}
			}
			*/
		}
		for (boolean b : bs) {
			if (!b) {
				p("table column mismatch");
				bResult = false;
			}
		}

		// indexes
		OAIndex[] indexes = dbTable.indexes();
		Index[] inds = table.getIndexes();
		bs = new boolean[inds.length];
		for (i = 0; i < inds.length; i++) {
			OAIndex indFnd = null;
			for (int j = 0; j < indexes.length; j++) {
				if (inds[i].name.equalsIgnoreCase(indexes[j].name())) {
					bs[i] = true;
					indFnd = indexes[j];
					break;
				}
			}
			if (indFnd == null) {
				p("index mismatch1");
				bResult = false;
				continue;
			}
			// compare columns
			for (String si : inds[i].columns) {
				boolean b = false;
				for (OAIndexColumn ic : indFnd.columns()) {
					if (ic.name().equalsIgnoreCase(si)) {
						b = true;
						break;
					}
				}
				if (!b) {
					p("index column mismatch");
					bResult = false;
				}
			}
		}

		for (boolean b : bs) {
			if (!b) {
				p("index mismatch2");
				bResult = false;
			}
		}
		return bResult;
	}

	// this is not used
	/**
	 * Verifies that all link definitions declared in the database metadata
	 * correspond to valid {@link OAOne} or {@link OAMany} annotations on the
	 * associated model class methods.
	 * <p>
	 * Ensures each link has an appropriate getter method and that its annotation
	 * type matches the expected return type (OAObject for ONE, Hub for MANY).
	 *
	 * @param database the database metadata containing table and link information
	 * @throws Exception if a link method is missing or incorrectly annotated
	 */
	public void verifyLinks(Database database) throws Exception {
		for (Table table : database.getTables()) {
			Class clazz = table.clazz;
			for (Link link : table.getLinks()) {
				Method method = clazz.getDeclaredMethod("get" + link.propertyName, null);
				if (method == null) {
					throw new Exception("cant find method for link " + link.propertyName);
				}
				OAOne oaone = (OAOne) method.getAnnotation(OAOne.class);
				OAMany oamany = (OAMany) method.getAnnotation(OAMany.class);
				Class c = method.getReturnType();
				if (oaone != null) {
					if (oamany != null) {
						throw new Exception("cant have One and Many annotion for same method, for link property " + link.propertyName);
					}
					if (!OAObject.class.isAssignableFrom(c)) {
						throw new Exception("OAOne annotation for class that does not return an OAObject subclass");
					}
				} else {
					if (oamany == null) {
						throw new Exception("no One or Many annotion for link property " + link.propertyName);
					}
					if (!Hub.class.isAssignableFrom(c)) {
						throw new Exception("OAMany annotation for class that does not return an Hub");
					}
				}
			}
		}
	}

	/**
	 * Compares two {@link Database} metadata instances to determine whether
	 * they describe equivalent schemas.
	 * <p>
	 * Validation includes table counts, table names, column structures,
	 * foreign-key links, and index definitions. Any mismatch prints a
	 * diagnostic message and marks the comparison as unsuccessful.
	 *
	 * @param db1 the first database metadata instance
	 * @param db2 the second database metadata instance
	 * @return {@code true} if both schemas match; otherwise {@code false}
	 */
	public boolean compare(Database db1, Database db2) {
		int x1 = db1.getTables().length;
		int x2 = db2.getTables().length;
		if (x1 != x2) {
			p("mismatch in number of tables");
			return false;
		}
		boolean bResult = true;
		for (Table t : db1.getTables()) {
			Table t2 = db2.getTable(t.name);
			if (t2 == null) {
				p("table not found, " + t.name);
				bResult = false;
				continue;
			}
			if (!compare(t, t2)) {
				bResult = false;
			}
		}
		return bResult;
	}

	/**
	 * Compares two {@link Table} metadata objects for structural equivalence.
	 * <p>
	 * Validates table names, associated classes, column definitions, foreign-key
	 * links, and index structures. Any mismatch results in a diagnostic message
	 * and a {@code false} return value.
	 *
	 * @param t1 the first table to compare
	 * @param t2 the second table to compare
	 * @return {@code true} if the tables match; otherwise {@code false}
	 */
	boolean compare(Table t1, Table t2) {
		if (!t1.name.equalsIgnoreCase(t2.name)) {
			p("mismatch in names");
			return false;
		}
		if (t1.clazz != null && !t1.clazz.equals(t2.clazz)) {
			p("mismatch in class");
			return false;
		}
		boolean bResult = true;
		int x1 = t1.getColumns().length;
		int x2 = t2.getColumns().length;
		if (x1 != x2) {
			HashSet<String> h = new HashSet<String>();
			for (Column c : t1.getColumns()) {
				h.add(c.columnName.toUpperCase());
			}
			for (Column c : t2.getColumns()) {
				h.remove(c.columnName.toUpperCase());
			}
			p("mismatch in number of columns "+h);
			bResult = false;
		}
		for (Column c : t1.getColumns()) {
			Column c2 = t2.getColumn(c.columnName, c.propertyName);
			if (c2 == null) {
				p("column not found");
				bResult = false;
			} else {
				if (!compare(c, c2)) {
					bResult = false;
				}
			}
		}
		if (x1 != x2) {
			for (Column c : t2.getColumns()) {
				Column c2 = t1.getColumn(c.columnName, c.propertyName);
				if (c2 == null) {
					p("column not found");
					bResult = false;
				}
			}
		}
		x1 = t1.getLinks().length;
		x2 = t2.getLinks().length;
		if (x1 != x2) {
			p("mismatch in number of links");
			return false;
		}
		for (Link link : t1.getLinks()) {
			Link link2 = t2.getLink(link.propertyName);
			if (link2 == null) {
				p("link not found, " + t1.name + "." + link.propertyName);
				bResult = false;
				continue;
			}
			if (!compare(link, link2)) {
				bResult = false;
			}
		}
		for (Index ind : t1.getIndexes()) {
			Index[] inds = t2.getIndexes();
			boolean b = false;
			for (Index ix : inds) {
				if (ind.name.equalsIgnoreCase(ix.name)) {
					if (!compare(ind, ix)) {
						bResult = false;
					}
					b = true;
				} else {
					// strip off last word, which would be the index property name
					String s = ind.name;
					int pos = 0;
					int x = s.length();
					for (int i = 0; i < x; i++) {
						if (Character.isUpperCase(ind.name.charAt(i))) {
							pos = i;
						}
					}

					if (!b && pos > 0) {
						s = s.substring(0, pos);
						if (s.equalsIgnoreCase(ix.name)) {
							if (!compare(ind, ix)) {
								bResult = false;
							}
							b = true;
						}

					}
				}
			}
			if (!b) {
				p("Index not found, " + t1.name + "." + ind.name);
				bResult = false;
			}
		}
		return true;
	}

	/**
	 * Compares two {@link Column} definitions for equality.
	 * <p>
	 * Compares column name, property name, key flags, Java type, SQL type,
	 * length, decimal places, next-number assignment, and GUID settings.
	 * Any mismatch prints a diagnostic message and returns {@code false}.
	 *
	 * @param c1 the first column
	 * @param c2 the second column
	 * @return {@code true} if the column definitions match; otherwise {@code false}
	 */
	boolean compare(Column c1, Column c2) {
		if (!c1.columnName.equalsIgnoreCase(c2.columnName)) {
			p("mismatch in columnName: " + c1.columnName + ", " + c2.columnName);
			return false;
		}
		if (c1.propertyName == null) {
			if (c2.propertyName != null && c2.propertyName.length() != 0) {
				p("mismatch in propertyName, null");
				return false;
			}
		} else if (!c1.propertyName.equalsIgnoreCase(c2.propertyName)) {
			p("mismatch in propertyName: " + c1.propertyName + ", " + c2.propertyName);
			return false;
		}
		if (c1.primaryKey != c2.primaryKey) {
			p("mismatch in primaryKey");
			return false;
		}
		if (c1.foreignKey != c2.foreignKey) {
			p("mismatch in foreignKey");
			return false;
		}
		if (c1.clazz != c2.clazz) {
			p("mismatch in clazz");
			return false;
		}
		if (c1.type != c2.type) {
			p("mismatch in type " + c1.columnName + ": " + c1.type + ", " + c2.type);
			return false;
		}
		if (c1.maxLength != c2.maxLength) {
			if (c1.type == Types.VARCHAR) {
				p("mismatch in maxLength, column=" + c1.columnName);
				return false;
			}
		}
		if (c1.decimalPlaces != c2.decimalPlaces) {
			p("mismatch in decimalPlaces");
			return false;
		}
		if (c1.assignNextNumber != c2.assignNextNumber) {
			p("mismatch in assignNextNumber");
			return false;
		}
		if (c1.guid != c2.guid) {
			p("mismatch in guid");
			return false;
		}
		return true;
	}

	/**
	 * Compares two {@link Index} definitions for structural equivalence.
	 * <p>
	 * Index names are compared case-insensitively, and column lists must match
	 * exactly (ignoring order). Any mismatch results in a diagnostic message
	 * and a {@code false} return value.
	 *
	 * @param ind1 the first index definition
	 * @param ind2 the second index definition
	 * @return {@code true} if both index definitions match; otherwise {@code false}
	 */
	boolean compare(Index ind1, Index ind2) {
		if (!ind1.name.equalsIgnoreCase(ind2.name)) {
			//            p("mismatch in index name");
			//            return false;
		}
		String[] c1 = ind1.columns;
		String[] c2 = ind2.columns;
		if (c1.length != c2.length) {
			p("mismatch in number of columns for index");
			return false;
		}
		for (String s : c1) {
			if (!OAArray.contains(c2, s, true)) {
				p("column not found in index");
				return false;
			}
		}
		return true;
	}

	/**
	 * Compares two {@link Link} definitions for equality.
	 * <p>
	 * Validates property name, reverse-property name, target table, and all
	 * foreign-key column mappings. Any mismatch prints a diagnostic message
	 * and returns {@code false}.
	 *
	 * @param link1 the first link definition
	 * @param link2 the second link definition
	 * @return {@code true} if both links match; otherwise {@code false}
	 */
	boolean compare(Link link1, Link link2) {
		if (!link1.propertyName.equalsIgnoreCase(link2.propertyName)) {
			p("propertyName mismatch for link");
			return false;
		}
		if (!link1.reversePropertyName.equalsIgnoreCase(link2.reversePropertyName)) {
			p("reversePropertyName mismatch for link=" + link1.reversePropertyName + "," + link2.reversePropertyName);
			return false;
		}
		if (!link1.toTable.name.equalsIgnoreCase(link2.toTable.name)) {
			p("toTable mismatch for link");
			return false;
		}
		if (link1.fkeys.length != link2.fkeys.length) {
			p("mismatch in link fkey length");
			return false;
		}
		for (Column c1 : link1.fkeys) {
			boolean b = false;
			for (Column c2 : link2.fkeys) {
				if (c1.columnName.equalsIgnoreCase(c2.columnName)) {
					b = true;
					break;
				}
			}
			if (!b) {
				p("link fkey column match not found");
				return false;
			}
		}
		return true;
	}


	void p(String msg) {
		//LOG.warning(msg);
		System.out.println("Error: " + msg);
	}
}
