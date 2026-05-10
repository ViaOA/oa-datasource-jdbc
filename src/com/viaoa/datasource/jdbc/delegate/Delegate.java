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
package com.viaoa.datasource.jdbc.delegate;

import java.lang.reflect.Method;

import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datasource.jdbc.db.Database;
import com.viaoa.datasource.jdbc.db.Index;
import com.viaoa.datasource.jdbc.db.Table;
import com.viaoa.datasource.jdbc.query.QueryConverter;

/**
 * JDBC helper utilities for OA, including property-length discovery and
 * database adjustments for case sensitivity.
 * <p>
 * Computes effective max length for a property from the active SELECT
 * column set, and normalizes case-insensitive columns (optionally maintaining
 * a lowercase mirror column) to align with database and index rules.
 * </p>
 */
public class Delegate {

	/**
	 * Returns the maximum length allowed for a property based on the active
	 * SELECT column set for the specified class.
	 * <p>
	 * If the property is not found or no explicit length applies, {@code -1}
	 * is returned.
	 *
	 * @param ds the JDBC data source
	 * @param c the OAObject class being queried
	 * @param propertyName the property name to inspect
	 * @return the maximum allowed length, or {@code -1} if not constrained
	 */
	public static int getPropertyMaxLength(OADataSourceJDBC ds, Class c, String propertyName) {
		QueryConverter qc = new QueryConverter(ds);
		Class[] classes = qc.getSelectClasses(c);

		for (int i = 0; classes != null && i < classes.length; i++) {
			Table table = ds.getDatabase().getTable(classes[i]);
			if (table == null) {
				continue;
			}
			Column[] columns = table.getSelectColumns();
			for (int ii = 0; columns != null && ii < columns.length; ii++) {
				if (propertyName.equalsIgnoreCase(columns[ii].propertyName)) {
					return getMaxLength(columns[ii]);
				}
			}
		}
		return -1;
	}

	/**
	 * Returns the maximum length allowed for a property by scanning all
	 * tables in the supplied database.
	 *
	 * @param database the database metadata container
	 * @param c the OAObject class being queried
	 * @param propertyName the property name to inspect
	 * @return the maximum allowed length, or {@code -1} if not constrained
	 */
	public static int getPropertyMaxLength(Database database, Class c, String propertyName) {
		for (Table table : database.getTables()) {
			Column[] columns = table.getSelectColumns();
			for (int ii = 0; columns != null && ii < columns.length; ii++) {
				if (propertyName.equalsIgnoreCase(columns[ii].propertyName)) {
					return getMaxLength(columns[ii]);
				}
			}
		}
		return -1;
	}

	/**
	 * Returns the effective maximum length for a column.
	 * <p>
	 * If the column maps to a {@link String} property and represents a
	 * VARCHAR or CHAR SQL type with a defined maximum length, that length
	 * is returned. Otherwise, {@code -1} is returned.
	 *
	 * @param c the column metadata
	 * @return the maximum length, or {@code -1} if not applicable
	 */
	public static int getMaxLength(Column c) {
		if (c == null) {
			return -1;
		}
		Method m = c.getGetMethod();
		if (m != null) {
			if (m.getReturnType().equals(String.class)) {
				if (c.maxLength < 256) {
					int type = c.getSqlType();
					if (type == 0 || type == java.sql.Types.VARCHAR || type == java.sql.Types.CHAR) {
						return c.maxLength;
					}
				}
			}
			return -1;
		}
		return c.maxLength;
	}

	/**
	 * Adjusts database metadata to account for case-insensitive column behavior.
	 * <p>
	 * This method updates index definitions and optional lowercase mirror
	 * columns based on database case-sensitivity rules to ensure consistent
	 * querying and indexing behavior.
	 *
	 * @param ds the JDBC data source whose database metadata will be adjusted
	 */
	public static void adjustDatabase(OADataSourceJDBC ds) {
		if (ds == null) {
			return;
		}
		Database database = ds.getDatabase();
		DBMetaData dbmd = ds.getDBMetaData();

		Table[] tables = database.getTables();
		for (int i = 0; i < tables.length; i++) {
			Table t = tables[i];
			Column[] columns = t.getColumns();
			for (int j = 0; j < columns.length; j++) {
				Column c = columns[j];
				if (c.type != java.sql.Types.VARCHAR) {
					continue;
				}
				if (c.caseSensitive) {
					continue;
				}

				boolean bLower = (c.columnLowerName != null && c.columnLowerName.toUpperCase().endsWith("LOWER"));
				if (!bLower && !dbmd.caseSensitive) {
					continue;
				}

				Index[] indexes = t.getIndexes();
				for (int k = 0; k < indexes.length; k++) {
					Index ind = indexes[k];
					for (int kk = 0; kk < ind.columns.length; kk++) {
						if (!ind.columns[kk].equalsIgnoreCase(c.columnName)) {
							if (!ind.columns[kk].equalsIgnoreCase(c.columnLowerName)) {
								continue;
							}
						}
						if (dbmd.caseSensitive) {
							c.columnLowerName = c.columnName + "Lower";
							ind.columns[kk] = c.columnName + "Lower";
						} else {
							ind.columns[kk] = c.columnName;
							c.columnLowerName = null;
						}
					}
				}
			}
		}
	}

}
