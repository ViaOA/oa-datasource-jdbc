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

import java.lang.reflect.Method;

import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectKeyService;
import com.viaoa.object.*;
import com.viaoa.reflect.OAReflect;
import com.viaoa.runtime.OARuntime;

/**
 * Represents a single column in a database table, including mapping
 * to an OAObject property and associated SQL metadata.
 * <p>
 * A {@code Column} maintains JDBC type, length, nullability,
 * and optional foreign-key relationships. It also caches reflection
 * accessors for property get/set methods to accelerate ORM binding.
 * </p>
 *
 * @see Table
 * @see Link
 * @see com.viaoa.datasource.jdbc.OADataSourceJDBC
 */
public class Column { // need to select all with properyName!=null

	/**
	 * The {@link Table} this column belongs to. Assigned during table
	 * initialization via {@code Table.setColumns()}.
	 */
	public Table table; // set by Table.setColumns()

	/**
	 * The database column name as defined in the physical table.
	 */
	public String columnName;

	/**
	 * Lower-cased version of {@link #columnName}, used for case-sensitive
	 * database engines that require normalized name comparisons.
	 */
	public String columnLowerName;

	/**
	 * The OAObject property that this column maps to. Used for reflection-based
	 * getter/setter lookup and ORM binding.
	 */
	public String propertyName;

	/**
	 * Indicates whether this column represents part of the primary key.
	 */
	public boolean primaryKey;

	/**
	 * True if this column participates in a foreign-key relationship.
	 * Set when table metadata is constructed.
	 */
	public boolean foreignKey;

	/**
	 * The Java type referenced by this column when used as a foreign key.
	 * Assigned during table/link initialization.
	 */
	public Class clazz;

	/**
	 * JDBC type value for this column (from {@link java.sql.Types}).
	 */
	public int type; // from sql.Types

	/**
	 * Maximum length allowed for this column, applicable to character or
	 * variable-length data types.
	 */
	public int maxLength;

	/**
	 * Number of decimal places for numeric columns. A value of -1 indicates
	 * that no fixed scale was defined.
	 */
	public int decimalPlaces = -1;

	/**
	 * True if this column auto-assigns sequential numbers for new objects.
	 * Used for primary-key autonumber columns.
	 */
	public boolean assignNextNumber; 

	/**
	 * True if this column stores a globally unique identifier (GUID/UUID).
	 */
	public boolean guid;

	/**
	 * Indicates whether this column stores Unicode character data.
	 */
	public boolean unicode;

	/**
	 * True if this column contains JSON data or is treated as a JSON column
	 * by the datasource.
	 */
	public boolean json;

	/**
	 * True if this column participates in a full-text index within the
	 * underlying database.
	 */
	public boolean fullTextIndex;

	/**
	 * Link metadata representing the foreign-key relationship associated
	 * with this column, if any.
	 */
	public Link fkeyLink;
	
	/**
	 * Position of this column within a foreign-key link definition.
	 */
	public int fkeyLinkPos;
	
	/**
	 * The referenced column in the target table when this column is part
	 * of a foreign-key relationship.
	 */
	public Column fkeyToColumn;

	/**
	 * Indicates whether comparisons involving this column should be treated
	 * as case-sensitive.
	 */
	public boolean caseSensitive;

	/**
	 * True if this column is read-only and should not be updated by the
	 * datasource during persistence.
	 */
	public boolean readOnly;

	/**
	 * Cached Java reflection method used to read the mapped OAObject property.
	 * Lazily initialized on first access via {@link #getGetMethod()}.
	 */
	Method methodGet;

	/**
	 * Cached Java reflection method used to write the mapped OAObject property.
	 * Lazily initialized on first access via {@link #getSetMethod()}.
	 */
	Method methodSet;

	/**
	 * Default constructor creating an empty Column definition with no name,
	 * type, or property mapping assigned.
	 */
	public Column() {
	}

	/**
	 * Constructs a Column with the specified database column name and default
	 * property name, type, and length values.
	 *
	 * @param columnName the database column name
	 */
	public Column(String columnName) {
		this(columnName, "", 0, 0);
	}

	/**
	 * Constructs a Column with the specified name and marks it as a foreign key.
	 *
	 * @param columnName the database column name
	 * @param fkey       true to flag this column as a foreign-key column
	 */
	public Column(String columnName, boolean fkey) {
		this(columnName, "", 0, 0);
		foreignKey = fkey;
	}

	/**
	 * Constructs a Column that maps the specified database column to the given
	 * OAObject property name.
	 *
	 * @param columnName  the database column name
	 * @param propertyName the mapped OAObject property
	 */
	public Column(String columnName, String propertyName) {
		this(columnName, propertyName, 0, 0);
	}

	/**
	 * Constructs a Column with the given database column name, mapped property,
	 * and JDBC type.
	 *
	 * @param columnName  the database column name
	 * @param propertyName the OAObject property name
	 * @param type         the JDBC type (from {@link java.sql.Types})
	 */
	public Column(String columnName, String propertyName, int type) {
		this.columnName = columnName;
		this.propertyName = propertyName;
		this.type = type;
	}

	/**
	 * Constructs a fully defined Column including JDBC type and maximum length.
	 *
	 * @param columnName  the database column name
	 * @param propertyName the OAObject property name
	 * @param type         the JDBC type
	 * @param maxLength    maximum column length
	 */
	public Column(String columnName, String propertyName, int type, int maxLength) {
		this.columnName = columnName;
		this.propertyName = propertyName;
		this.type = type;
		this.maxLength = maxLength;
	}

	/**
	 * Returns the JDBC type assigned to this column.
	 *
	 * @return the JDBC {@link java.sql.Types} value
	 */
	public int getSqlType() {
		return type;
	}

	/**
	 * Returns the cached getter method for reading the OAObject property mapped
	 * to this column. If not already cached and the column belongs to a table,
	 * the method is resolved using OAObjectInfoDelegate.
	 *
	 * @return the property getter method, or null if unavailable
	 */
	public Method getGetMethod() {
		if (methodGet == null && table != null) {
			Class clazz = table.getSupportClass();
			if (clazz != null && propertyName != null && propertyName.length() != 0) {
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
			    methodGet = og.objectsInternal().callObjectInfoGetMethod(clazz, "get" + propertyName);
				//was: methodGet = OAReflect.getMethod(clazz, "get" + propertyName);
			}
		}
		return methodGet;
	}

	/**
	 * Returns the cached setter method for writing the OAObject property mapped
	 * to this column. If not already cached and the column belongs to a table,
	 * the method is resolved using OAObjectInfoDelegate.
	 *
	 * @return the property setter method, or null if unavailable
	 */
	public Method getSetMethod() {
		if (methodSet == null && table != null) {
			Class clazz = table.getSupportClass();
			if (clazz != null && propertyName != null && propertyName.length() != 0) {
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
			    methodSet = og.objectsInternal().callObjectInfoGetMethod(clazz, "set" + propertyName);
				//was: methodSet = OAReflect.getMethod(clazz, "set" + propertyName);
			}
		}
		return methodSet;
	}
}
