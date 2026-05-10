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

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datasource.jdbc.db.Table;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectReflectService;
import com.viaoa.graph.service.object.OAObjectSaveService;
import com.viaoa.object.OAObject;
import com.viaoa.runtime.OARuntime;

/**
 * Assigns and tracks auto-numbered primary key values for JDBC-backed entities.
 * <p>
 * Seeds per-table counters from the database using a MAX(...) query, then
 * dispenses IDs atomically from an in-memory cache. When a GUID prefix is
 * configured, values are emitted as {@code "<guid>-<seq>"}; otherwise an
 * {@link Integer} is assigned directly. ID assignment is performed while
 * {@code OAObjectDSDelegate.setAssigningId(...)} is true to preserve lifecycle semantics.
 * </p>
 *
 * @see com.viaoa.datasource.jdbc.db.DBMetaData
 * @see com.viaoa.datasource.jdbc.db.Table
 * @see com.viaoa.datasource.jdbc.db.Column
 */
public class AutonumberDelegate {
	private static Logger LOG = Logger.getLogger(AutonumberDelegate.class.getName());

	/**
	 * Map of table name (upper-case) to the next sequence number to assign.
	 * <p>
	 * Values are initialized from the database using a MAX query and
	 * incremented atomically in memory.
	 */
	private static final Map<String, AtomicInteger> hmTabelNextSeq = new ConcurrentHashMap<String, AtomicInteger>(39, .75f); // Table.name.upper, Integer

	/**
	 * Assigns an auto-numbered primary key value to the specified object.
	 * <p>
	 * If GUID mode is enabled for the column, the generated sequence number
	 * is prefixed with the configured GUID value.
	 *
	 * @param ds the JDBC data source
	 * @param object the object receiving the assigned value
	 * @param table the database table metadata
	 * @param column the primary key column metadata
	 */
	public static void assignNumber(OADataSourceJDBC ds, OAObject object, Table table, Column column) {
		// LOG.finer("table="+table.name+", column="+column.columnName);
		int id = getNextNumber(ds, table, column, true);
		// LOG.finer("table="+table.name+", column="+column.columnName+", nextId="+id);
		DBMetaData dbmd = ds.getDBMetaData();
		Object value;

		if (column.guid && dbmd.guid != null) {
			value = dbmd.guid + "-" + id;
		} else {
			value = Integer.valueOf(id);
		}

		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object);
		try {
			og.objectsInternal().callObjectDSSetAssigningId(object, true);
			og.objectsInternal().callObjectReflectSetProperty(object, column.propertyName, value, null);
		} finally {
			og.objectsInternal().callObjectDSSetAssigningId(object, false);
		}
	}

	/**
	 * Verifies that the supplied ID has been accounted for in the autonumber
	 * sequence and advances the next value if required.
	 *
	 * @param ds the JDBC data source
	 * @param object the object associated with the ID, or {@code null}
	 * @param table the database table metadata
	 * @param column the primary key column metadata
	 * @param id the ID value that has already been used
	 */
	public static void verifyNumberUsed(OADataSourceJDBC ds, OAObject object, Table table, Column column, final int id) {
		if (table == null || table.name == null || column == null) {
			return;
		}
		// LOG.finer("table="+table.name+", column="+column.columnName+", verifyId="+id);
		for (;;) {
			int idNext = getNextNumber(ds, table, column, false);
			if (id < idNext) {
				break;
			}
			AtomicInteger ai = hmTabelNextSeq.get(table.name.toUpperCase());
			if (ai == null || ai.compareAndSet(idNext, id + 1)) {
				break; // else need to try again
			}
		}
	}

	/**
	 * Updates the autonumber sequence to ensure that the next assigned value
	 * will be greater than or equal to the specified number.
	 *
	 * @param ds the JDBC data source
	 * @param table the database table metadata
	 * @param nextNumberToUse the next number that should be considered used
	 */
	public static void setNextNumber(OADataSourceJDBC ds, Table table, int nextNumberToUse) {
		if (table == null || table.name == null) {
			return;
		}
		LOG.fine("table=" + table.name + ", nextNumberToUse=" + nextNumberToUse);
		Column[] columns = table.getColumns();
		for (int i = 0; columns != null && i < columns.length; i++) {
			Column column = columns[i];
			if (column.primaryKey) {
				verifyNumberUsed(ds, null, table, column, nextNumberToUse);
				break;
			}
		}
	}

	/**
	 * Returns the next available autonumber value for the specified table
	 * and primary key column.
	 *
	 * @param ds the JDBC data source
	 * @param table the database table metadata
	 * @param pkColumn the primary key column metadata
	 * @param bAutoIncrement {@code true} to increment the sequence; {@code false} to read only
	 * @return the next available number
	 */
	public static int getNextNumber(final OADataSourceJDBC ds, final Table table, final Column pkColumn, final boolean bAutoIncrement) {
		int x = _getNextNumber(ds, table, pkColumn, bAutoIncrement);
		//LOG.finer("table="+table+", name="+table.name+", bAutoIncrement="+bAutoIncrement+", returning="+x);
		return x;
	}

	//========================= Utilities ===========================
	/**
	 * Internal implementation used to retrieve and optionally increment the
	 * autonumber sequence for a table.
	 *
	 * @param ds the JDBC data source
	 * @param table the database table metadata
	 * @param pkColumn the primary key column metadata
	 * @param bAutoIncrement {@code true} to increment the sequence
	 * @return the current or next autonumber value
	 */
	private static int _getNextNumber(final OADataSourceJDBC ds, final Table table, final Column pkColumn, final boolean bAutoIncrement) {
		if (table == null || table.name == null || pkColumn == null) {
			return -1;
			// LOG.finer("table="+table.name+", column="+pkColumn.columnName+", bAutoIncrement="+bAutoIncrement);
		}

		final String hashId = table.name.toUpperCase();
		AtomicInteger ai = hmTabelNextSeq.computeIfAbsent(hashId, k -> {
			int max = 0;
			if (ds == null) {
				max = 1;
			} else {
				DBMetaData dbmd = ds.getDBMetaData();
				String query = "";
				if (pkColumn.guid && dbmd.guid != null && dbmd.guid.length() > 0) {
					query = getMaxGuidQuery(dbmd, table, pkColumn);
				} else {
					query = getMaxIdQuery(dbmd, table, pkColumn);
				}

				Statement statement = null;
				try {
					statement = ds.getStatement(query);
					ResultSet rs = statement.executeQuery(query);
					if (rs.next()) {
						max = (rs.getInt(1) + 1);
					}
					rs.close();
					LOG.fine("table=" + table.name + ", column=" + pkColumn.columnName + ", max=" + max + ", query=" + query
							+ ", hash=" + hmTabelNextSeq);
				} catch (Exception e) {
					throw new RuntimeException("OADataSource.getNextNumber() failed for " + table.name + " Query:" + query, e);
				} finally {
					if (statement != null) {
						ds.releaseStatement(statement);
					}
				}
			}
			AtomicInteger aix = new AtomicInteger(max);
			return aix;
		});

		int max;
		if (bAutoIncrement) {
			max = ai.getAndIncrement();
		} else {
			max = ai.get();
		}

		//LOG.warning("table="+table.name+", column="+pkColumn.columnName+", max="+max+", ai="+ai+", bAutoIncrement="+bAutoIncrement);
		return max;
	}

	/**
	 * Builds a database-specific SQL query used to determine the maximum
	 * numeric portion of a GUID-prefixed primary key.
	 *
	 * @param dbmd database metadata
	 * @param table the database table metadata
	 * @param dbcolumn the primary key column metadata
	 * @return the SQL query used to determine the maximum GUID-based value
	 */
	protected static String getMaxGuidQuery(DBMetaData dbmd, Table table, Column dbcolumn) {
		// ACCESS Version to get string value of seq number
		String column = dbcolumn.columnName;
		String s;
		String from = " from " + dbmd.leftBracket + table.name + dbmd.rightBracket;
		String where;
		if (dbmd.databaseType == dbmd.ACCESS) {
			s = "select max(val(right$(" + column + ", len(" + column + ")-" + (dbmd.guid.length() + 1) + ") ))";
			where = " WHERE " + column + " like '" + dbmd.guid + "-%'";
		} else if (dbmd.databaseType == dbmd.DERBY) {
			s = "select max(integer(substr(" + column + ", " + (dbmd.guid.length() + 2) + ")))";
			where = " WHERE " + column + " like '" + dbmd.guid + "-%'";
		} else if (dbmd.databaseType == dbmd.SQLSERVER) {
			s = "select max(right(" + column + ", len(" + column + ")-" + (dbmd.guid.length() + 1) + "))";
			where = " WHERE " + column + " like '" + dbmd.guid + "-%'";
		} else {
			// MYSQL
			s = "select max(convert(right(" + column + ", length(" + column + ")-" + (dbmd.guid.length() + 1) + "), UNSIGNED INTEGER))";
			where = " WHERE " + column + " like '" + dbmd.guid + "-%'";
		}
		s = s + from + where;
		;
		LOG.fine("table=" + table.name + ", column=" + dbcolumn.columnName + ", query=" + s);

		return s;
	}

	/**
	 * Builds a database-specific SQL query used to determine the maximum
	 * numeric primary key value for a table.
	 *
	 * @param dbmd database metadata
	 * @param table the database table metadata
	 * @param dbcolumn the primary key column metadata
	 * @return the SQL query used to determine the maximum numeric ID
	 */
	protected static String getMaxIdQuery(DBMetaData dbmd, Table table, Column dbcolumn) {
		String column = dbcolumn.columnName;
		String s;
		if (dbmd.databaseType == dbmd.ACCESS) {
			// ACCESS Version to get string value of seq number
			s = "select max(val(" + column + "))";
		} else if (dbmd.databaseType == dbmd.MYSQL) {
			s = "SELECT MAX(CONVERT(" + column + ", UNSIGNED INTEGER))";
		} else {
			s = "select max(" + column + ")";
		}

		s += " FROM " + table.name;
		LOG.fine("table=" + table.name + ", column=" + dbcolumn.columnName + ", query=" + s);
		return s;
	}
}
