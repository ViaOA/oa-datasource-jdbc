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
package com.viaoa.datasource.jdbc.db;

import java.util.Enumeration;
import java.util.Hashtable;

import com.viaoa.lang.OAArray;
import com.viaoa.object.OAObject;

/**
 * Defines the in-memory representation of a database within the OA JDBC subsystem.
 * <p>
 * A {@code Database} manages a collection of {@link Table} instances,
 * mapping Java model classes to physical tables and caching the associations.
 * It provides fast lookup by class or name and ensures bidirectional linkage
 * across table hierarchies (including inheritance support).
 * </p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Register and retrieve {@link Table} instances by class or name.</li>
 *   <li>Preserve superclass/subclass mappings for inherited entities.</li>
 *   <li>Provide consistent schema visibility to {@link com.viaoa.datasource.jdbc.OADataSourceJDBC}.</li>
 * </ul>
 *
 * <h2>Thread-Safety</h2>
 * Once initialized, this class is read-only and safe for concurrent access.
 *
 * @see Table
 * @see com.viaoa.datasource.jdbc.OADataSourceJDBC
 */
public class Database {

	/**
	 * The ordered list of {@link Table} definitions registered with this
	 * database. Updated when tables are added or removed.
	 */
	private Table[] tables = new Table[0];
	
	/**
	 * Lookup table mapping Java model classes to their corresponding
	 * {@link Table} metadata. Allows fast table retrieval by class.
	 */
	private Hashtable hash = new Hashtable();

	/**
	 * Constant representing a generic database type. Currently unused.
	 */
	public static final int DATABASE_GENERIC = 0;

	/**
	 * Constant representing a Microsoft Access database type. Currently unused.
	 */
	public static final int DATABASE_ACCESS = 1;

	/**
	 * Maximum number of defined database type constants. Currently unused.
	 */
	public static final int DATABASE_MAX = 2;

	/**
	 * Returns the {@link Table} mapped to the specified Java class.
	 *
	 * @param clazz the model class whose table is requested
	 * @return the matching Table, or null if none exists
	 */
	public Table getTable(Class clazz) {
		if (clazz == null) {
			return null;
		}
		return (Table) hash.get(clazz);
	}

	/**
	 * Finds a {@link Table} by case-insensitive name lookup. Searches first
	 * through the class-based hash map, then through the ordered table list.
	 *
	 * @param name the table name to look up
	 * @return the matching Table, or null if not found
	 */
	public Table getTable(String name) {
		if (name == null) {
			return null;
		}

		if (hash != null) {
			Enumeration enumx = hash.elements();
			for (; enumx.hasMoreElements();) {
				Table t = (Table) enumx.nextElement();
				if (name.equalsIgnoreCase(t.name)) {
					return t;
				}
			}
		}
		for (int i = 0; tables != null && i < tables.length; i++) {
			if (name.equalsIgnoreCase(tables[i].name)) {
				return tables[i];
			}
		}
		return null;
	}

	/**
	 * Removes the specified table from this database. Updates both the ordered
	 * table list and the class-lookup hash map.
	 *
	 * @param table the table to remove
	 */
	public void removeTable(Table table) {
		this.tables = (Table[]) OAArray.removeValue(Table.class, this.tables, table);
		hash.remove(table.clazz);
		//todo: include superclasses, like add table
	}

	/**
	 * Registers a new {@link Table} with this database. Adds it to the ordered
	 * list and the class-lookup hash.  
	 * <p>
	 * If the table's class has a superclass that is also mapped, the superclass
	 * table's {@code subclasses} array is updated to include this class.
	 *
	 * @param table the table to add
	 */
	public void addTable(Table table) {
		if (table == null) {
			return;
		}
		this.tables = (Table[]) OAArray.add(Table.class, this.tables, table);

		if (table.clazz != null) {
			hash.put(table.clazz, table);

			Class sc = table.clazz.getSuperclass();
			if (sc != null && !sc.equals(OAObject.class)) {
				Table stable = (Table) hash.get(sc);
				if (stable != null) {
					int x = (stable.subclasses == null) ? 0 : stable.subclasses.length;
					Class[] cc = new Class[x + 1];
					if (x > 0) {
						System.arraycopy(stable.subclasses, 0, cc, 0, x);
					}
					cc[x] = table.clazz;
					stable.subclasses = cc;
				}
			}
		}
	}

	/**
	 * Replaces the current table list with the supplied array. Clears the
	 * class-lookup hash and re-registers each table using {@link #addTable}.
	 *
	 * @param tables the array of tables to install
	 */
	public void setTables(Table[] tables) {
		if (tables == null) {
			tables = new Table[0];
		}
		hash.clear();

		for (int i = 0; tables != null && i < tables.length; i++) {
			addTable(tables[i]);
		}
	}

	/**
	 * Returns the list of all {@link Table} objects registered in this database.
	 *
	 * @return the table array
	 */
	public Table[] getTables() {
		return tables;
	}
}
