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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.logging.Logger;

import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datasource.jdbc.db.Link;
import com.viaoa.datasource.jdbc.db.Table;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectReflectService;
import com.viaoa.lang.OAString;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.runtime.OARuntime;

/**
 * Generates and executes {@code INSERT} statements, including key assignment
 * and vendor-aware parameterization.
 * <p>
 * Skips DB-generated keys when supported; otherwise assigns autonumbers.
 * Parameterizes long strings and byte arrays; optionally casts JSON values
 * using vendor tokens. When databases are case-sensitive, maintains lowercase
 * mirror columns as configured. Can enrich missing foreign-key columns from link metadata.
 * </p>
 */
public class InsertDelegate {

	private static Logger LOG = Logger.getLogger(InsertDelegate.class.getName());

	/**
	 * Inserts the specified object into the database without including
	 * referenced foreign-key values.
	 *
	 * @param ds the JDBC data source
	 * @param obj the object to insert
	 */
	public static void insertWithoutReferences(OADataSourceJDBC ds, OAObject obj) {
		if (obj == null) {
			return;
		}
		insert(ds, obj, obj.getClass(), false);
	}

	/**
	 * Inserts the specified object into the database, including referenced
	 * foreign-key values when applicable.
	 *
	 * @param ds the JDBC data source
	 * @param object the object to insert
	 */
	public static void insert(OADataSourceJDBC ds, OAObject object) {
		if (object == null) {
			return;
		}
		insert(ds, object, object.getClass(), true);
	}

	/**
	 * Recursively inserts the specified object for the given class and its
	 * superclasses.
	 * <p>
	 * Honors datasource write restrictions, handles auto-assigned keys,
	 * and delegates SQL generation and execution.
	 *
	 * @param ds the JDBC data source
	 * @param oaObj the object being inserted
	 * @param clazz the class whose table mapping is being inserted
	 * @param bIncludeRefereces {@code true} to include foreign-key references
	 */
	private static void insert(OADataSourceJDBC ds, OAObject oaObj, Class clazz, boolean bIncludeRefereces) {
		if (ds.getIgnoreWrites()) {
			return;
		}
		if (ds.getReadOnly()) {
			throw new RuntimeException("datasource is set to readOnly=true");
		}
		Class c = clazz.getSuperclass();
		if (c != null && !c.equals(OAObject.class)) {
			insert(ds, oaObj, c, bIncludeRefereces);
		}

		Column columnSkip = null;
		if (!ds.getAssignIdOnCreate() && ds.getDBMetaData().supportsAutoAssign) {
			Table table = ds.getDatabase().getTable(clazz);
			if (table != null) {
				Column[] columns = table.getColumns();
				for (int i = 0; columns != null && i < columns.length; i++) {
					if (columns[i].assignNextNumber) {
						if (oaObj.isNull(columns[i].propertyName)) {
							columnSkip = columns[i]; // assigned by DS
						}
						break;
					}
				}
			}
		}

		Object[] objs = null;
		try {
			objs = getInsertSQL(ds, oaObj, clazz, columnSkip, bIncludeRefereces);
			Object[] params = null;
			ArrayList al = (ArrayList) objs[1];
			if (al != null) {
				int x = al.size();
				params = new Object[x];
				for (int i = 0; i < x; i++) {
					params[i] = al.get(i);
				}
			}

			performInsert(ds, oaObj, (String) objs[0], params, columnSkip);
		} catch (Exception e) {
			if (objs == null || objs.length == 0) {
				objs = new String[] { "no sql generated" };
			}
			//LOG.log(Level.WARNING, "insert(), sql="+objs[0], e);
			throw new RuntimeException("Error on insert, sql=" + objs[0], e);
		}
	}

	/**
	 * Builds an {@code INSERT} SQL statement and parameter list for the specified
	 * object and class mapping.
	 *
	 * @param ds the JDBC data source
	 * @param oaObj the object being inserted
	 * @param clazz the class whose table mapping is used
	 * @param columnSkip optional column skipped due to database-generated keys
	 * @param bIncludeRefereces {@code true} to include foreign-key values
	 * @return an array containing the SQL string and parameter value list
	 * @throws Exception if table metadata cannot be resolved
	 */
	private static Object[] getInsertSQL(OADataSourceJDBC ds, OAObject oaObj, Class clazz, Column columnSkip, boolean bIncludeRefereces)
			throws Exception {
		Table table = ds.getDatabase().getTable(clazz);
		if (table == null) {
			throw new Exception("cant find table for Class " + clazz.getName());
		}

		Column[] columns = table.getColumns();
		StringBuffer str = new StringBuffer(128);
		StringBuffer values = new StringBuffer(128);
		ArrayList<Object> alValue = null;
		String value;
		DBMetaData dbmd = ds.getDBMetaData();
		boolean bFkeysIncluded = true;
		for (int i = 0; columns != null && i < columns.length; i++) {
			Column column = columns[i];
			if (column == columnSkip) {
				continue;
			}
			if (column.propertyName == null || column.propertyName.length() == 0) {
                if (column.foreignKey) bFkeysIncluded = false;
				continue;
			}
			if (column.readOnly) {
				continue;
			}

			Object obj = oaObj.getProperty(column.propertyName);
			// see if column needs to be assigned to a seq number
			// support for DB generated keys
			if (column.primaryKey && column.assignNextNumber) {
				boolean b = false;
				if (obj == null) {
					b = true;
				} else if (obj instanceof Number) {
					if (((Number) obj).intValue() == 0) {
						b = true;
					} else {
						// this will make sure that the assigned number does not mess up the nextNumber generator
						AutonumberDelegate.verifyNumberUsed(ds, oaObj, table, column, ((Number) obj).intValue());
					}
				}
				if (b) {
					if (dbmd.supportsAutoAssign) {
						continue; // generated by DB
					}
					AutonumberDelegate.assignNumber(ds, oaObj, table, column);
					obj = oaObj.getProperty(column.propertyName);
				}
			}

			boolean bNull = (obj == null);

			String origValue = null;
			// 20100514
			boolean byteArray = (obj instanceof byte[]);
			if (byteArray) {
				if (alValue == null) {
					alValue = new ArrayList(3);
				}
				alValue.add(obj);
				value = "?";
			} else {
				boolean bOver512 = obj != null && obj instanceof String && ((String) obj).length() > 512;
				// this will convert to SQL string
				value = ConverterDelegate.convertToString(	dbmd, obj, !bOver512, Delegate.getMaxLength(column), column.decimalPlaces,
															column);
				origValue = value;
				if (value != null && bOver512) {
					if (alValue == null) {
						alValue = new ArrayList<Object>(3);
					}
					alValue.add(value);
					value = "?";
				}
			}
			if (str.length() > 0) {
				str.append(", ");
				values.append(", ");
			}
			str.append(dbmd.leftBracket + column.columnName.toUpperCase() + dbmd.rightBracket);

			// 20221206
			if (value != null && column.json && OAString.isNotEmpty(dbmd.jsonCast)) {
				value += dbmd.jsonCast;
			}
			values.append(value);

			// check for case sensitive column
			if (!byteArray && dbmd.caseSensitive) {
				String colNameLower = column.columnLowerName;
				if (OAString.isNotEmpty(colNameLower) && !colNameLower.equalsIgnoreCase(column.columnName)) {
					value = origValue;
					if (value != null) {
						value = value.toLowerCase();
					}
					if (value != null && value.length() > 512) {
						alValue.add(value);
						value = "?";
					}
					str.append(", ");
					values.append(", ");
					str.append(dbmd.leftBracket + colNameLower.toUpperCase() + dbmd.rightBracket);
					values.append(value);
				}
			}
		}

		/* 20230918 OAObject now has fkey properties */
		if (!bFkeysIncluded) {
    		// update Fkeys
    		Link[] links = table.getLinks();
    		for (int i = 0; bIncludeRefereces && links != null && i < links.length; i++) {
    			if (links[i].fkeys == null || (links[i].fkeys.length == 0)) {
    				continue;
    			}
    			if (links[i].fkeys[0].primaryKey) {
    				continue; // one2many, or one2one (where Key is the fkey)
    			}
    
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(oaObj);
    			OAObjectKey key = og.objectsInternal().callObjectReflectGetPropertyObjectKey(oaObj, links[i].propertyName);
    			if (key == null) {
    				continue; // null
    			}
    			Object[] ids;
    			ids = key.getObjectIds();
    
    			Column[] fkeys = table.getLinkToColumns(links[i], links[i].toTable);
    			if (fkeys == null) {
    				continue;
    			}
    			if (fkeys.length != links[i].fkeys.length) {
    				continue;
    			}
    			for (int j = 0; j < fkeys.length; j++) {
    				Object objProperty = ((ids == null) || (j >= ids.length)) ? null : ids[j];
    				value = ConverterDelegate.convert(dbmd, fkeys[j], objProperty);
    				if (str.length() > 0) {
    					str.append(", ");
    					values.append(", ");
    				}
    
    				str.append(dbmd.leftBracket + links[i].fkeys[j].columnName.toUpperCase() + dbmd.rightBracket);
    				values.append(value);
    			}
    		}
		}
		
		
		str = new StringBuffer("INSERT INTO " + dbmd.leftBracket + table.name.toUpperCase() + dbmd.rightBracket + " (" + str + ") VALUES ("
				+ values + ")");
		return new Object[] { new String(str), alValue };
	}

	/**
	 * Executes the supplied {@code INSERT} SQL statement using either a
	 * {@link Statement} or {@link PreparedStatement}.
	 * <p>
	 * Supports batch execution, parameter binding, structured logging,
	 * and retrieval of database-generated key values.
	 *
	 * @param ds the JDBC data source
	 * @param oaObj the object being inserted
	 * @param sqlInsert the INSERT SQL statement
	 * @param params optional parameter values
	 * @param columnAutoGen primary key column generated by the database, or {@code null}
	 * @throws Exception if the insert fails or no row is inserted
	 */
	private static void performInsert(final OADataSourceJDBC ds, final OAObject oaObj, final String sqlInsert, final Object[] params,
			final Column columnAutoGen)
			throws Exception {

		/*
		OAObjectKey key = OAObjectKeyDelegate.getKey(oaObj);
		String s = String.format("Insert, class=%s, id=%s, sql=%s",
		        OAString.getClassName(oaObj.getClass()),
		        key.toString(),
		        sqlInsert
		);
		OAObjectInfo oi = OAObjectInfoDelegate.getOAObjectInfo(oaObj);
		if (oi.getUseDataSource()) {
		    OAObject.OALOG.fine(s);
		}
		
		LOG.fine(s);
		
		DBLogDelegate.logInsert(sqlInsert, params);
		*/
		DBLogDelegate.logInsert(sqlInsert, params);

		final boolean bUseBatch = ds.isAllowingBatch() && (columnAutoGen == null);

		Statement statement = null;
		PreparedStatement preparedStatement = null;
		try {

			int x = 0;
			if (params != null && params.length > 0) {
				if (bUseBatch) {
					preparedStatement = ds.getBatchPreparedStatement(sqlInsert);
				} else {
					preparedStatement = ds.getPreparedStatement(sqlInsert, (columnAutoGen != null));
				}

				for (int i = 0; i < params.length; i++) {
					if (params[i] instanceof String) {
						// 20221206
						preparedStatement.setString(i + 1, (String) params[i]);
						/*was
						preparedStatement.setAsciiStream(	i + 1, new StringBufferInputStream((String) params[i]),
															((String) params[i]).length());
						*/
					} else {
						preparedStatement.setBytes(i + 1, (byte[]) (params[i]));
					}
				}

				if (bUseBatch) {
					preparedStatement.addBatch();
					x = 1;
				} else {
					x = preparedStatement.executeUpdate();
				}
			} else {
				if (bUseBatch) {
					statement = ds.getBatchStatement(sqlInsert);
					statement.addBatch(sqlInsert);
					x = 1;
				} else {
					statement = ds.getStatement(sqlInsert);
					int param = (columnAutoGen != null) ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS;
					x = statement.executeUpdate(sqlInsert, param);
				}
			}
			if (x != 1) {
				LOG.warning("could not insert, sql=" + sqlInsert);
				throw new Exception("row was not INSERTed, no exception thrown");
			}

			if (columnAutoGen != null) {
				ResultSet rs;
				if (preparedStatement != null) {
					rs = preparedStatement.getGeneratedKeys();
				} else {
					rs = statement.getGeneratedKeys();
				}

				if (rs.next()) {
					Object val = rs.getObject(1);

					final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(oaObj);
					try {
						og.objectsInternal().callObjectDSSetAssigningId(oaObj, true);
						oaObj.setProperty(columnAutoGen.propertyName, val);
					} finally {
						og.objectsInternal().callObjectDSSetAssigningId(oaObj, false);
					}
				}
				rs.close();
			}
		} finally {
			if (statement != null) {
				if (!bUseBatch) {
					ds.releaseStatement(statement);
				}
			}
			if (preparedStatement != null) {
				if (!bUseBatch) {
					ds.releasePreparedStatement(preparedStatement);
				}
			}
		}
	}

}
