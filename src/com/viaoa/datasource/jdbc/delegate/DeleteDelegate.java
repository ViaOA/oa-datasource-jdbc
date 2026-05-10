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

import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.Table;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectKeyService;
import com.viaoa.object.OAObject;
import com.viaoa.runtime.OARuntime;

/**
 * Generates and executes {@code DELETE} statements for OAObjects,
 * walking the class inheritance hierarchy when tables are split across
 * super/subclasses.
 * <p>
 * Builds a primary-key {@code WHERE} clause from table metadata and delegates
 * literal conversion to {@link ConverterDelegate}. New (unsaved) objects are ignored.
 * </p>
 */
public class DeleteDelegate {
	private static Logger LOG = Logger.getLogger(DeleteDelegate.class.getName());

	/**
	 * Deletes the database rows associated with the specified object.
	 * <p>
	 * If the object is {@code null} or marked as new (unsaved), no action is taken.
	 * Otherwise, deletion begins with the object's concrete class and continues
	 * up the inheritance hierarchy.
	 *
	 * @param ds the JDBC data source
	 * @param object the object to delete
	 */
	public static void delete(OADataSourceJDBC ds, OAObject object) {
		if (object == null) {
			return;
		}
		if (object.getNew()) {
			final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object);
			LOG.finer("delete called on a new object, class=" + object.getClass().getName() + ", key=" + og.objectsInternal().callObjectKeyGetKey(object));
			return;
		}
		delete(ds, object, object.getClass());
	}

	/**
	 * Deletes the database row for the specified class mapping of an object.
	 * <p>
	 * This method generates and executes a {@code DELETE} statement for the
	 * table mapped to {@code clazz}, then recursively deletes rows for
	 * superclass mappings.
	 *
	 * @param ds the JDBC data source
	 * @param oaObj the object being deleted
	 * @param clazz the class whose table mapping is being deleted
	 */
	private static void delete(OADataSourceJDBC ds, OAObject oaObj, Class clazz) {
		if (ds.getIgnoreWrites()) {
			return;
		}
		if (ds.getReadOnly()) {
			throw new RuntimeException("datasource is set to readOnly=true");
		}

		String sql = null;
		try {
			sql = getDeleteSQL(ds, oaObj, clazz);

			/*
			OAObjectKey key = OAObjectKeyDelegate.getKey(oaObj);
			String s = String.format("Update, class=%s, id=%s, sql=%s",
			        OAString.getClassName(oaObj.getClass()),
			        key.toString(),
			        sql
			);
			OAObjectInfo oi = OAObjectInfoDelegate.getOAObjectInfo(oaObj);
			if (oi.getUseDataSource()) {
			    OAObject.OALOG.fine(s);
			}
			LOG.fine(s);
			*/
			performDelete(ds, sql);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "exception trying to delete, sql=" + sql, e);
			throw new RuntimeException(e);
		}
		Class c = clazz.getSuperclass();
		if (c != null && !c.equals(OAObject.class)) {
			delete(ds, oaObj, c);
		}
	}

	/**
	 * Builds a {@code DELETE} SQL statement for the specified object and class.
	 * <p>
	 * The {@code WHERE} clause is constructed using all primary key columns
	 * defined for the table mapped to {@code clazz}.
	 *
	 * @param ds the JDBC data source
	 * @param oaObj the object being deleted
	 * @param clazz the class whose table mapping is used
	 * @return the generated {@code DELETE} SQL statement
	 * @throws Exception if no table mapping exists for the class
	 */
	private static String getDeleteSQL(OADataSourceJDBC ds, OAObject oaObj, Class clazz) throws Exception {
		Table table = ds.getDatabase().getTable(clazz);
		if (table == null) {
			throw new Exception("cant find table for Class " + clazz.getName());
		}
		Column[] columns = table.getColumns();
		StringBuffer where = new StringBuffer(64);
		for (int i = 0; columns != null && i < columns.length; i++) {
			Column column = columns[i];
			if (!column.primaryKey || column.propertyName == null || column.propertyName.length() == 0) {
				continue;
			}

			Object obj = oaObj.getProperty(column.propertyName);

			String op = "=";
			String value;
			if (obj == null) {
				op = "IS";
			}
			value = ConverterDelegate.convert(ds.getDBMetaData(), column, obj);
			value = ds.getDBMetaData().leftBracket + column.columnName.toUpperCase() + ds.getDBMetaData().rightBracket + " " + op + " "
					+ value;

			if (where.length() > 0) {
				where.append(" AND ");
			}
			where.append(value);
		}
		String str = "DELETE FROM " + ds.getDBMetaData().leftBracket + table.name.toUpperCase() + ds.getDBMetaData().rightBracket
				+ " WHERE " + where;
		return str;
	}

	/**
	 * Executes the supplied {@code DELETE} SQL statement.
	 * <p>
	 * If batch operations are enabled, the statement is added to the current
	 * batch; otherwise, it is executed immediately.
	 *
	 * @param ds the JDBC data source
	 * @param str the {@code DELETE} SQL statement
	 * @throws Exception if a JDBC error occurs
	 */
	private static void performDelete(OADataSourceJDBC ds, String str) throws Exception {
		LOG.fine(str);
		final boolean bUseBatch = ds.isAllowingBatch();
		Statement statement = null;
		try {
			// DBLogDelegate.logDelete(str);
			int x;
			if (bUseBatch) {
				statement = ds.getBatchStatement(str);
				statement.addBatch(str);
				x = 1;
			} else {
				statement = ds.getStatement(str);
				x = statement.executeUpdate(str);
			}
			if (x != 1) {
				LOG.warning("row was not DELETEd, no exception thrown");
			}
		} finally {
			if (statement != null && !bUseBatch) {
				ds.releaseStatement(statement);
			}
		}
	}

}
