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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

import com.viaoa.lang.OAArray;
import com.viaoa.reflect.OAReflect;

/**
 * Represents a relational database table and its associated metadata.
 * <p>
 * Each {@code Table} defines its columns, relationships ({@link Link}),
 * and indexes. The table also references its corresponding Java class
 * used to materialize OAObjects.
 * </p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Tracks {@link Column} definitions and primary/foreign keys.</li>
 *   <li>Defines {@link Link} relationships for joins and navigation.</li>
 *   <li>Resolves constructor references for object instantiation.</li>
 *   <li>Provides helper methods to compute select and key columns.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * {@code Table} instances are created and registered by {@link Database}
 * and consumed by {@link com.viaoa.datasource.jdbc.OADataSourceJDBC}
 * when generating SQL for persistence operations.
 *
 * @see Column
 * @see Link
 * @see Index
 * @see Database
 */
public class Table {

	/**
	 * The database table name as it appears in the relational schema.
	 */
	public String name;
	
	/**
	 * The Java class associated with this table, used for instantiating
	 * OAObjects and resolving property/column mappings.
	 */
	public Class clazz;
	
	/**
	 * Collection of {@link Link} relationships originating from this table.
	 * Each link defines a foreign-key reference to another table.
	 */
	private Link[] links = new Link[0];
	
	/**
	 * Array of {@link Column} objects representing the table's columns,
	 * including primary keys, foreign keys, and mapped properties.
	 */
	private Column[] columns = new Column[0];
	
	/**
	 * Array of {@link Index} definitions associated with this table,
	 * including manually defined and foreign-key-supporting indexes.
	 */
	private Index[] indexes = new Index[0];
	
	/**
	 * Flag indicating whether this table represents a pure link/join table
	 * (used for many-to-many relationships).
	 */
	public boolean bLink; // is this a link table
	
	/**
	 * Array of Java subclasses associated with this table’s OA class.
	 * Populated by {@link Database} after all tables have been registered.
	 */
	public Class[] subclasses; // set by Database when all tables are loaded
	
	/**
	 * Cached zero-argument constructor for the associated Java class.
	 * Resolved lazily by {@link #getConstructor()}.
	 */
	Constructor constructor;

	/**
	 * Runtime-only list of classes involved in SELECT operations for this table.
	 * Not persisted; used by query-generation logic.
	 */
	public transient Class[] selectClasses;
	
	/**
	 * Runtime-only array of {@link Column} objects used when building SELECT
	 * statements for this table.
	 */
	public transient Column[] selectColumnArray;
	
	/**
	 * Cached comma-separated SQL list of columns used in SELECT statements.
	 * Computed at runtime for performance.
	 */
	public transient String selectColumns;
	
	/**
	 * Cached comma-separated SQL list of primary-key columns used for
	 * identity resolution during SELECT operations.
	 */
	public transient String selectPKColumns;
	
	/**
	 * Reference to the {@link DataAccessObject} responsible for executing
	 * database operations for this table. Runtime-only.
	 */
	public transient DataAccessObject dataAccessObject;

	/**
	 * Default constructor creating an uninitialized {@code Table}.
	 * Table name, class, and metadata must be assigned manually.
	 */
	public Table() {
	}

	/**
	 * Constructs a {@code Table} with the given database table name and
	 * associated Java class.
	 *
	 * @param name the relational table name
	 * @param clazz the Java class representing table rows
	 */
	public Table(String name, Class clazz) {
		this.name = name;
		this.clazz = clazz;
	}

	/**
	 * Constructs a {@code Table} and designates whether it is a link/join table.
	 *
	 * @param name the table name
	 * @param isLinkTable {@code true} if this is a many-to-many join table
	 */
	public Table(String name, boolean isLinkTable) {
		this.name = name;
		this.bLink = isLinkTable;
	}

	/**
	 * Replaces all index definitions for this table.
	 *
	 * @param indexes array of index definitions
	 */
	public void setIndexes(Index[] indexes) {
		this.indexes = indexes;
	}

	/**
	 * Adds an {@link Index} definition to this table.
	 *
	 * @param index the index to add
	 */
	public void addIndex(Index index) {
		int x = indexes.length;
		Index[] ixs = new Index[x + 1];
		System.arraycopy(indexes, 0, ixs, 0, x);
		ixs[x] = index;
		indexes = ixs;
	}

	/**
	 * Returns all index definitions associated with this table.
	 *
	 * @return array of {@link Index} entries
	 */
	public Index[] getIndexes() {
		return indexes;
	}

	/**
	 * Retrieves the {@link Link} whose referenced table matches the given Java class.
	 *
	 * @param clazz the class of the target OAObject table
	 * @return the matching link, or {@code null} if none found
	 */
	public Link getLink(Class clazz) {
		for (int i = 0; links != null && i < links.length; i++) {
			if (links[i].toTable.clazz.equals(clazz)) {
				return links[i];
			}
		}
		return null;
	}

	/**
	 * Retrieves the {@link Link} whose reference property name matches the given value,
	 * ignoring case.
	 *
	 * @param name the reference property name to search for
	 * @return the matching {@code Link}, or {@code null} if none exists
	 */
	public Link getLink(String name) {
		for (int i = 0; links != null && i < links.length; i++) {
			if (links[i].propertyName.equalsIgnoreCase(name)) {
				return links[i];
			}
		}
		return null;
	}

	/**
	 * Returns all {@link Link} relationships defined for this table.
	 *
	 * @return array of {@code Link} objects
	 */
	public Link[] getLinks() {
		return links;
	}

	/**
	 * Replaces all link definitions for this table and triggers a metadata update
	 * to re-evaluate foreign-key and type relationships.
	 *
	 * @param links array of link definitions
	 */
	public void setLinks(Link[] links) {
		if (links == null) {
			links = new Link[] {};
		}
		this.links = links;
		updateLinks(true);
	}

	/**
	 * Adds a new {@link Link} to this table, referencing a single foreign-key column
	 * by index into this table's column list.
	 *
	 * @param propertyName name of the reference property
	 * @param toTable the table being referenced
	 * @param reversePropertyName the reverse reference name on the target table
	 * @param columnFkey index of the foreign-key column in this table
	 */
	public void addLink(String propertyName, Table toTable, String reversePropertyName, int columnFkey) {
		addLink(propertyName, toTable, reversePropertyName, new int[] { columnFkey });
	}

	/**
	 * Adds a new {@link Link} referencing one or more foreign-key columns in this table.
	 *
	 * @param propertyName name of the reference property
	 * @param toTable the table being referenced
	 * @param reversePropertyName reverse reference name in the target table
	 * @param columnFkeys array of column indexes representing the foreign-key columns
	 */
	public void addLink(String propertyName, Table toTable, String reversePropertyName, int[] columnFkeys) {
		Link link = new Link(propertyName, reversePropertyName, toTable);
		int x = columnFkeys.length;
		Column[] cols = new Column[x];
		for (int i = 0; i < x; i++) {
			cols[i] = getColumns()[columnFkeys[i]];
		}
		link.fkeys = cols;

		if (links == null) {
			links = new Link[] { link };
		} else {
			x = links.length;
			Link[] newLinks = new Link[x + 1];
			System.arraycopy(links, 0, newLinks, 0, x);
			newLinks[x] = link;
			links = newLinks;
		}
		updateLinks(true);
	}

	/**
	 * Returns the Java class associated with this table, used for instantiation
	 * and property/column mapping.
	 *
	 * @return the supporting Java class
	 */
	public Class getSupportClass() {
		return clazz;
	}

	/**
	 * Updates the Java class associated with this table.
	 *
	 * @param clazz the supporting Java class
	 */
	public void setSupportClass(Class clazz) {
		this.clazz = clazz;
	}

	/**
	 * Adds all columns from the given array to this table, invoking
	 * {@link #addColumn(Column)} for each column.
	 *
	 * @param columns array of {@link Column} definitions
	 */
	public void setColumns(Column[] columns) {
		for (int i = 0; columns != null && i < columns.length; i++) {
			addColumn(columns[i]);
		}
	}

	/**
	 * Adds a single {@link Column} to this table and updates metadata such as
	 * foreign-key flags and value types inferred from setter methods.
	 *
	 * @param column the column to add
	 */
	public void addColumn(Column column) {
		this.columns = (Column[]) OAArray.add(Column.class, this.columns, column);
		if (column.table != this) {
			column.foreignKey = false;
			column.table = this;
			Method method = column.getSetMethod();
			if (method != null) {
				Class[] cs = method.getParameterTypes();
				if (cs.length > 0) {
					Class c = OAReflect.getClassWrapper(cs[0]);
					column.clazz = c;
				}
			}
		}
	}

	/**
	 * Refreshes link and column metadata after links are added or updated.
	 * <p>
	 * Responsibilities include:
	 * <ul>
	 *   <li>Marking foreign-key columns.</li>
	 *   <li>Synchronizing column types with the referenced table's primary key columns.</li>
	 *   <li>Setting back-references and link metadata.</li>
	 * </ul>
	 *
	 * @param bUpdateToLinks whether to propagate updates to referenced tables
	 */
	protected void updateLinks(boolean bUpdateToLinks) {
		// 1: flag all columns that are a Fkey
		for (int i = 0; links != null && i < links.length; i++) {
			links[i].table = this;
			for (int k = 0; links[i].fkeys != null && k < links[i].fkeys.length; k++) {
				if (!links[i].fkeys[k].primaryKey) {
					links[i].fkeys[k].foreignKey = true;
				}
			}
		}

		// update column type info to match the fkey type
		for (int i = 0; links != null && i < links.length; i++) {
			Link link = links[i];
			Column[] cols1 = link.fkeys;
			Column[] cols2 = getLinkToColumns(link, link.toTable);
			if (cols1 != null && cols2 != null) {
				if (cols1.length != cols2.length) {
					throw new RuntimeException("Links do not have same amount of fkeys and pkeys");
				}
				for (int j = 0; j < cols1.length; j++) {
					if (cols1[j].primaryKey) {
						if (bUpdateToLinks) {
							link.toTable.updateLinks(false);
						}
						continue;
					}
					// 20090301
					cols1[j].type = cols2[j].type;
					cols1[j].clazz = cols2[j].clazz;
					cols1[j].fkeyLink = link;
					cols1[j].fkeyLinkPos = j;
					cols1[j].fkeyToColumn = cols2[j];
				}
			}
		}
	}

	/**
	 * Returns all {@link Column} objects defined for this table.
	 *
	 * @return array of columns
	 */
	public Column[] getColumns() {
		return columns;
	}

	/**
	 * Finds a column by its database column name or property name.
	 *
	 * @param name the database column name (optional)
	 * @param propName the Java property name (optional)
	 * @return the matching {@link Column}, or {@code null} if none found
	 */
	public Column getColumn(String name, String propName) {
		if (name != null && name.length() == 0) {
			name = null;
		}
		if (propName != null && propName.length() == 0) {
			propName = null;
		}
		Column[] cols = getColumns();
		for (int i = 0; cols != null && i < cols.length; i++) {
			if (name != null && name.equalsIgnoreCase(cols[i].columnName)) {
				return cols[i];
			}
			if (propName != null && propName.equalsIgnoreCase(cols[i].propertyName)) {
				return cols[i];
			}
		}
		return null;
	}

	/**
	 * Returns the zero-based position of the given column within this table's
	 * column array.
	 *
	 * @param col the column to locate
	 * @return column index, or -1 if not found
	 */
	public int getColumnPosition(Column col) {
		if (col == null) {
			return -1;
		}
		Column[] cols = getColumns();
		for (int i = 0; cols != null && i < cols.length; i++) {
			if (cols[i] == col) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Retrieves the {@link Column} whose Java property name matches the given
	 * parameter, ignoring case.
	 *
	 * @param propName the property name to search for
	 * @return the matching column, or {@code null} if none exists
	 */
	public Column getPropertyColumn(String propName) {
		Column[] cols = getColumns();
		for (int i = 0; cols != null && i < cols.length; i++) {
			if (propName != null && propName.equalsIgnoreCase(cols[i].propertyName)) {
				return cols[i];
			}
		}
		return null;
	}

	/**
	 * Lazily resolves and returns the zero-argument constructor for the Java class
	 * represented by this table. The constructor is cached for future access.
	 *
	 * @return the no-argument {@link Constructor} of the table's Java class
	 * @throws RuntimeException if the constructor cannot be obtained
	 */
	public Constructor getConstructor() {
		if (constructor == null) {
			try {
				if (clazz != null) {
					constructor = clazz.getConstructor(new Class[] {});
				}
			} catch (NoSuchMethodException e) {
				throw new RuntimeException("OADataSourceJDBC.update() cant get constructor() for class " + clazz.getName(), e);
			}
		}

		return constructor;
	}

	/**
	 * Computes and returns the columns that should be included in SELECT queries.
	 * Includes:
	 * <ul>
	 *   <li>All columns mapped to properties.</li>
	 *   <li>All primary-key and foreign-key columns.</li>
	 * </ul>
	 *
	 * @return array of selected columns
	 */
	public Column[] getSelectColumns() {
		ArrayList<Column> al = new ArrayList<Column>(15);
		for (int i = 0; columns != null && i < columns.length; i++) {
			Column column = columns[i];
			if (column.propertyName == null || column.propertyName.length() == 0) {
				// get all columns that are foreign keys or primary keys
				if (!column.primaryKey && !column.foreignKey) {
					continue;
				}
			}
			al.add(column);
		}
		Column[] cols = new Column[al.size()];
		al.toArray(cols);
		return cols;
	}

	/**
	 * Returns the subset of this table's columns that are marked as primary keys.
	 *
	 * @return array of primary-key columns
	 */
	public Column[] getPrimaryKeyColumns() {
		ArrayList<Column> al = new ArrayList<Column>(3);
		for (int i = 0; columns != null && i < columns.length; i++) {
			Column column = columns[i];
			if (column.primaryKey) {
				al.add(column);
			}
		}
		Column[] cols = new Column[al.size()];
		al.toArray(cols);
		return cols;
	}

	/**
	 * Resolves and returns the columns in the referenced {@code toTable} that
	 * correspond to the foreign keys of the provided {@code link}.
	 *
	 * @param link the link representing the relationship
	 * @param toTable the referenced table
	 * @return array of corresponding columns, or {@code null} if not applicable
	 */
	public Column[] getLinkToColumns(Link link, Table toTable) {
		if (link == null || toTable == null) {
			return null;
		}
		String revProp = link.reversePropertyName;
		Link[] links = toTable.getLinks();
		Column[] hold = null;
		for (int i = 0; links != null && i < links.length; i++) {
			if (links[i].toTable == this) {
				hold = links[i].fkeys;
				if (revProp != null && links[i].propertyName.equalsIgnoreCase(revProp)) {
					break;
				}
			}
		}
		return hold;
	}

	/**
	 * Locates the reverse {@link Link} in the destination table that points back
	 * to this table, matching the link’s {@code reversePropertyName}.
	 *
	 * @param link the forward link definition
	 * @return the reverse link, or {@code null} if none exists
	 */
	public Link getReverseLink(Link link) {
		String revProp = link.reversePropertyName;
		Link[] links = link.toTable.getLinks();
		Column[] hold = null;
		for (int i = 0; links != null && i < links.length; i++) {
			if (links[i].toTable == this) {
				if (revProp != null && links[i].propertyName.equalsIgnoreCase(revProp)) {
					return links[i];
				}
			}
		}
		return null;
	}

	/**
	 * Assigns the {@link DataAccessObject} responsible for database operations
	 * for this table.
	 *
	 * @param dao the DAO instance to associate with this table
	 */
	public void setDataAccessObject(DataAccessObject dao) {
		dataAccessObject = dao;
	}

	/**
	 * Returns the {@link DataAccessObject} assigned to this table for performing
	 * database operations such as SELECT, INSERT, UPDATE, and DELETE.
	 *
	 * @return the associated {@link DataAccessObject}, or {@code null} if none assigned
	 */
	public DataAccessObject getDataAccessObject() {
		return dataAccessObject;
	}
}
