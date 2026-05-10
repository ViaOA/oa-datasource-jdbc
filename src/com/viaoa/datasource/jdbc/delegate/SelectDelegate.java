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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.viaoa.datasource.OADataSourceIterator;
import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datasource.jdbc.db.DataAccessObject;
import com.viaoa.datasource.jdbc.db.Link;
import com.viaoa.datasource.jdbc.db.ManyToMany;
import com.viaoa.datasource.jdbc.db.Table;
import com.viaoa.datasource.jdbc.query.QueryConverter;
import com.viaoa.datasource.jdbc.query.ResultSetIterator;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectKeyService;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.runtime.OARuntime;
import com.viaoa.transaction.OATransaction;

/**
 * Composes and executes {@code SELECT} queries for JDBC DataSources.
 * <p>
 * Converts OA query expressions into SQL via {@code QueryConverter},
 * supports a DISTINCT + primary-key reselect flow, prepared-statement SQL
 * caching, DAO-based fast materialization, and direct by-key fetches.
 * Results stream through {@code ResultSetIterator}.
 * </p>
 */
public class SelectDelegate {
	private static Logger LOG = Logger.getLogger(SelectDelegate.class.getName());

	/**
	 * Cache of prepared SELECT SQL strings keyed by {@link WhereObjectSelect}
	 * definitions for where-object based queries.
	 */
	private static final Map<WhereObjectSelect, String> hmPreparedStatementSql = new ConcurrentHashMap<WhereObjectSelect, String>();

	/**
	 * Cache of prepared SELECT SQL strings keyed by class for primary-key lookups
	 * using clean (non-dirty) reads.
	 */
	private static final Map<Class, String> hmPreparedStatementSqlx = new ConcurrentHashMap<Class, String>();

	/**
	 * Cache of prepared SELECT SQL strings keyed by class for dirty reads
	 * using primary-key lookups.
	 */
	private static final Map<Class, String> hmPreparedStatementSqlxDirty = new ConcurrentHashMap<Class, String>();

	/**
	 * Cache of column arrays associated with dirty prepared SELECT statements.
	 */
	private static final Map<Class, Column[]> hmPreparedStatementSqlxDirtyColumns = new ConcurrentHashMap<Class, Column[]>();

	/*
	public static Iterator select(OADataSourceJDBC ds, Class clazz, String queryWhere, String queryOrder, int max, boolean bDirty) {
		return select(ds, clazz, queryWhere, (Object[]) null, queryOrder, max, bDirty);
	}
	*/

	/*
	public static Iterator select(OADataSourceJDBC ds, Class clazz, String queryWhere, Object param, String queryOrder, int max, boolean bDirty) {
		Object[] params = null;
		if (param != null) params = new Object[] {param};
		return select(ds, clazz, queryWhere, params, queryOrder, max, bDirty);
	}
	*/

	/**
	 * Executes a SELECT query for the specified class using a where clause,
	 * parameters, and ordering.
	 *
	 * @param ds the JDBC data source
	 * @param clazz the class being selected
	 * @param queryWhere the WHERE clause expression
	 * @param params query parameter values
	 * @param queryOrder ORDER BY clause
	 * @param max maximum number of rows to return
	 * @param bDirty {@code true} to allow dirty reads
	 * @return an iterator over the result set, or {@code null} if not applicable
	 */
	public static OADataSourceIterator select(OADataSourceJDBC ds, Class clazz, String queryWhere, Object[] params, String queryOrder,
			int max, boolean bDirty) {
		if (ds == null) {
			return null;
		}
		if (clazz == null) {
			return null;
		}
		Table table = ds.getDatabase().getTable(clazz);
		if (table == null) {
			return null;
		}
		QueryConverter qc = new QueryConverter(ds);
		String[] queries = getSelectSQL(qc, ds, clazz, queryWhere, params, queryOrder, max, bDirty);

		ResultSetIterator rsi;
		DataAccessObject dao = table.getDataAccessObject();
		if (!bDirty && dao != null) {
			rsi = new ResultSetIterator(ds, clazz, dao, queries[0], queries[1], max);
		} else {
			Column[] columns = qc.getSelectColumnArray(clazz);
			if (queries[1] != null) {
				// this will take 2 queries.  The first will only select pkey columns.
				//   the second query will then select the record using the pkey values in the where clause.
				rsi = new ResultSetIterator(ds, clazz, columns, queries[0], queries[1], max);
			} else {
				rsi = new ResultSetIterator(ds, clazz, columns, queries[0], max);
			}
		}
		rsi.setDirty(bDirty);
		return rsi;
	}

	/**
	 * Builds one or two SELECT SQL statements for the given query definition.
	 * <p>
	 * When DISTINCT is required, a two-step primary-key select followed by
	 * a full-row select is generated.
	 *
	 * @param qc the query converter
	 * @param ds the JDBC data source
	 * @param clazz the class being selected
	 * @param queryWhere the WHERE clause
	 * @param params query parameters
	 * @param queryOrder ORDER BY clause
	 * @param max maximum number of rows
	 * @param bDirty {@code true} to allow dirty reads
	 * @return an array containing the primary SQL and optional secondary SQL
	 */
	private static String[] getSelectSQL(QueryConverter qc, OADataSourceJDBC ds, Class clazz, String queryWhere, Object[] params,
			String queryOrder, int max, boolean bDirty) {
		String[] queries = new String[2];

		queries[0] = qc.convertToSql(clazz, queryWhere, params, queryOrder);
		if (qc.getUseDistinct()) {
			// distinct query will also need to have the order by keys
			String s = " ORDER BY ";
			int x = queries[0].indexOf(s);
			if (x > 0) {
				x += s.length();
				s = queries[0].substring(x);

				// need to remove ASC, DESC
				// todo: this might not be needed anymore
				StringTokenizer st = new StringTokenizer(s, ", ", false);
				String s1 = null;
				for (; st.hasMoreElements();) {
					String s2 = (String) st.nextElement();
					String s3 = s2.toUpperCase();
					if (s3.equals("ASC")) {
						continue;
					}
					if (s3.equals("DESC")) {
						continue;
					}
					if (s1 == null) {
						s1 = s2;
					} else {
						s1 += ", " + s2;
					}
				}
				s = ", " + s1;
			} else {
				s = "";
			}

			// this will take 2 queries.  The first will only select pkey columns.
			//   the second query will then select the record using the pkey values in the where clause.
			queries[0] = "SELECT " + ds.getDBMetaData().distinctKeyword + " " + qc.getPrimaryKeyColumns(clazz) + s + " " + queries[0];

			final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
			OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(clazz);
			String[] ids = oi.getIdProperties();
			params = new Object[ids.length];
			queries[1] = "";
			for (int i = 0; ids != null && i < ids.length; i++) {
				if (i > 0) {
					queries[1] += " AND ";
				}
				queries[1] += ids[i] + " = ?";
				params[i] = "7"; // fake out/position holder
			}
			queries[1] = qc.convertToSql(clazz, queries[1], params, null);
			queries[1] = "SELECT " + qc.getSelectColumns(clazz, bDirty) + " " + queries[1];
			queries[1] = OAString.convert(queries[1], "7", "?");
		} else {
			queries[0] = "SELECT " + qc.getSelectColumns(clazz, bDirty) + " " + queries[0];
			//was: queries[0] = "SELECT " + getMax(ds,max) + qc.getSelectColumns(clazz, bDirty) + " " + queries[0];
		}
		return queries;
	}

	/**
	 * Composite key used to cache prepared SELECT SQL for where-object queries.
	 */
	private static class WhereObjectSelect {

		/**
		 * Target class being selected.
		 */
		private Class clazz;
		
		/**
		 * Class of the where-object used in the query.
		 */
		private Class whereClazz;
		
		/**
		 * Property name used to relate the where-object to the target class.
		 */
		private String propertyFromWhereObject;

		/**
		 * Creates a new where-object select key.
		 *
		 * @param clazz the target class
		 * @param whereClazz the where-object class
		 * @param propertyFromWhereObject the linking property name
		 */
		public WhereObjectSelect(Class clazz, Class whereClazz, String propertyFromWhereObject) {
			this.clazz = clazz;
			this.whereClazz = whereClazz;
			this.propertyFromWhereObject = propertyFromWhereObject;
		}

		/**
		 * Compares this instance to another for equality.
		 *
		 * @param obj the object to compare
		 * @return {@code true} if all key fields are equal
		 */
		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof WhereObjectSelect)) {
				return false;
			}
			WhereObjectSelect x = (WhereObjectSelect) obj;

			if (clazz != x.clazz) {
				if (clazz == null || x.clazz == null) {
					return false;
				}
				if (!clazz.equals(x.clazz)) {
					return false;
				}
			}
			if (whereClazz != x.whereClazz) {
				if (whereClazz == null || x.whereClazz == null) {
					return false;
				}
				if (!whereClazz.equals(x.whereClazz)) {
					return false;
				}
			}
			if (propertyFromWhereObject != x.propertyFromWhereObject) {
				if (propertyFromWhereObject == null || x.propertyFromWhereObject == null) {
					return false;
				}
				if (!propertyFromWhereObject.equals(x.propertyFromWhereObject)) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Returns a hash code based on the key fields.
		 *
		 * @return the computed hash code
		 */
		@Override
		public int hashCode() {
			int x = 0;
			if (clazz != null) {
				x += clazz.hashCode();
			}
			if (whereClazz != null) {
				x += whereClazz.hashCode();
			}
			if (propertyFromWhereObject != null) {
				x += propertyFromWhereObject.hashCode();
			}
			return x;
		}
	}

	/**
	 * Executes a SELECT query using a where-object relationship.
	 *
	 * @param ds the JDBC data source
	 * @param clazz the class being selected
	 * @param whereObject the related where-object
	 * @param propertyFromWhereObject property linking the where-object
	 * @param queryWhere additional WHERE clause
	 * @param params query parameters
	 * @param extraWhere extra SQL WHERE conditions
	 * @param queryOrder ORDER BY clause
	 * @param max maximum number of rows
	 * @param bDirty {@code true} to allow dirty reads
	 * @return an iterator over the result set, or {@code null} if not applicable
	 */
	public static OADataSourceIterator select(OADataSourceJDBC ds, Class clazz,
			OAObject whereObject, String propertyFromWhereObject,
			String queryWhere, Object[] params,
			String extraWhere,
			String queryOrder, int max, boolean bDirty) {

		/*was:
		public static OADataSourceIterator select(OADataSourceJDBC ds, Class clazz, OAObject whereObject, String extraWhere, Object[] params,
				String propertyFromWhereObject, String queryOrder, int max, boolean bDirty) {
			// dont need to select if master object (whereObject) is new
		 */
		if (whereObject == null || whereObject.getNew()) {
			return null;
		}

		Table table = ds.getDatabase().getTable(clazz);
		if (table == null) {
			return null;
		}
		DataAccessObject dao = table.getDataAccessObject();

		if (dao == null || whereObject == null || OAString.isEmpty(propertyFromWhereObject) || (params != null && params.length > 0)
				|| max > 0) {
			QueryConverter qc = new QueryConverter(ds);
			String query = getSelectSQL(ds, qc, clazz, whereObject, propertyFromWhereObject, queryWhere, params, extraWhere, queryOrder,
										max, bDirty);

			ResultSetIterator rsi;
			if (!bDirty && dao != null) {
				rsi = new ResultSetIterator(ds, clazz, dao, query, null, max);
			} else {
				Column[] columns = qc.getSelectColumnArray(clazz);
				rsi = new ResultSetIterator(ds, clazz, columns, query, max);
			}
			rsi.setDirty(bDirty);
			return rsi;
		}

		WhereObjectSelect wos = new WhereObjectSelect(clazz, whereObject == null ? null : whereObject.getClass(), propertyFromWhereObject);
		String query = bDirty ? null : hmPreparedStatementSql.get(wos);

		if (query == null) {
			QueryConverter qc = new QueryConverter(ds);
			query = "SELECT " + qc.getSelectColumns(clazz, bDirty);
			query += " " + qc.convertToPreparedStatementSql(clazz, whereObject, propertyFromWhereObject, queryWhere, params, extraWhere,
															queryOrder);

			params = qc.getArguments();
			if (whereObject != null && (params == null || params.length == 0)) {
				return null; // null reference
			}
			if (!bDirty) {
				hmPreparedStatementSql.put(wos, query);
			}
		} else {
			final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(whereObject);
			OAObjectKey key = og.objectsInternal().callObjectKeyGetKey(whereObject);
			params = key.getObjectIds();
		}

		ResultSetIterator rsi;
		if (!bDirty && dao != null) {
			rsi = new ResultSetIterator(ds, clazz, dao, query, params);
		} else {
			QueryConverter qc = new QueryConverter(ds);
			Column[] columns = qc.getSelectColumnArray(clazz);
			rsi = new ResultSetIterator(ds, clazz, columns, query, params, max);
		}
		rsi.setDirty(bDirty);
		return rsi;
	}

	/**
	 * Selects a single object by its primary key.
	 *
	 * @param ds the JDBC data source
	 * @param clazz the object class
	 * @param key the object key
	 * @param bDirty {@code true} to allow dirty reads
	 * @return an iterator positioned at the selected object
	 * @throws Exception if selection fails
	 */
	public static OADataSourceIterator selectObject(OADataSourceJDBC ds, Class clazz, OAObjectKey key, boolean bDirty) throws Exception {
		if (ds == null) {
			return null;
		}
		if (clazz == null) {
			return null;
		}

		Table table = ds.getDatabase().getTable(clazz);
		if (table == null) {
			return null;
		}
		DataAccessObject dao = table.getDataAccessObject();

		ResultSetIterator rsi;
		if (!bDirty && dao != null) {
			String sql = hmPreparedStatementSqlx.computeIfAbsent(clazz, ckey -> {
				String sqlx = dao.getSelectColumns();
				sqlx = "SELECT " + sqlx;
				sqlx += " FROM " + table.name + " WHERE ";

				// query columns must match same order as used by objKey properties
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
				OAObjectInfo oi = og.objectsInternal().callObjectInfoGetObjectInfo(clazz);
				boolean b = false;
				String[] ss = oi.getKeyProperties();
				if (ss != null) {
					for (String propName : ss) {
						if (!b) {
							b = true;
						} else {
							sqlx += " AND ";
						}
						Column col = table.getPropertyColumn(propName);
						sqlx += col.columnName + " = ?";
					}
				}
				return sqlx;
			});
			rsi = new ResultSetIterator(ds, clazz, dao, sql, key.getObjectIds());
		} else {
			String sql = hmPreparedStatementSqlxDirty.computeIfAbsent(clazz, ckey -> {
				QueryConverter qc = new QueryConverter(ds);
				String sqlNew = "SELECT " + qc.getSelectColumns(clazz, bDirty); // could use dao
				sqlNew += " FROM " + table.name + " WHERE ";

				// query columns must match same order as used by objKey properties
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
				OAObjectInfo oi = og.objectsInternal().callObjectInfoGetObjectInfo(clazz);
				boolean b = false;
				String[] ss = oi.getKeyProperties();
				if (ss != null) {
					for (String propName : ss) {
						if (b) sqlNew += " AND ";
						b = true;
						Column col = table.getPropertyColumn(propName);
						sqlNew += col.columnName + " = ?";
					}
				}
				Column[] columns = qc.getSelectColumnArray(clazz);
				hmPreparedStatementSqlxDirtyColumns.put(clazz, columns);
				return sqlNew;
			});			
			
			Column[] columns = hmPreparedStatementSqlxDirtyColumns.get(clazz);			
			rsi = new ResultSetIterator(ds, clazz, columns, sql, key.getObjectIds(), 0);
			
		}
		rsi.setDirty(bDirty);
		return rsi;
	}

	/**
	 * Builds a SELECT SQL statement for a where-object based query.
	 *
	 * @param ds the JDBC data source
	 * @param qc the query converter
	 * @param clazz the class being selected
	 * @param whereObject the related where-object
	 * @param propertyFromWhereObject property linking the where-object
	 * @param queryWhere WHERE clause
	 * @param args query parameters
	 * @param extraWhere extra SQL conditions
	 * @param queryOrder ORDER BY clause
	 * @param max maximum number of rows
	 * @param bDirty {@code true} to allow dirty reads
	 * @return the generated SELECT SQL statement
	 */
	public static String getSelectSQL(OADataSourceJDBC ds, QueryConverter qc, Class clazz,
			OAObject whereObject, String propertyFromWhereObject,
			String queryWhere, Object[] args,
			String extraWhere,
			String queryOrder, int max, boolean bDirty) {

		if (propertyFromWhereObject == null) {
			propertyFromWhereObject = "";
		}
		String query = "SELECT " + qc.getSelectColumns(clazz, bDirty);
		//was: String query = "SELECT " + getMax(ds,max) + qc.getSelectColumns(clazz, bDirty);
		query += " " + qc.convertToSql(clazz, whereObject, propertyFromWhereObject, queryWhere, args, extraWhere, queryOrder);
		return query;
	}

	/**
	 * Executes a passthrough SELECT query fragment.
	 *
	 * @param ds the JDBC data source
	 * @param clazz the class being selected
	 * @param query SQL fragment beginning after SELECT
	 * @param max maximum number of rows
	 * @param bDirty {@code true} to allow dirty reads
	 * @return an iterator over the result set
	 */
	public static Iterator selectPassthru(OADataSourceJDBC ds, Class clazz, String query, int max, boolean bDirty) {
		Table table = ds.getDatabase().getTable(clazz);
		if (table == null) {
			return null;
		}

		QueryConverter qc = new QueryConverter(ds);

		query = qc.getSelectColumns(clazz, bDirty) + " " + query;

		ResultSetIterator rsi;
		DataAccessObject dao = table.getDataAccessObject();
		if (!bDirty && dao != null) {
			rsi = new ResultSetIterator(ds, clazz, dao, query, null, max);
		} else {
			Column[] columns = qc.getSelectColumnArray(clazz);
			rsi = new ResultSetIterator(ds, clazz, columns, "SELECT " + query, max);
			//was: rsi = new ResultSetIterator(ds, clazz, columns, "SELECT "+getMax(ds,max)+query, max);
		}
		rsi.setDirty(bDirty);
		return rsi;
	}

	/* use statement.setMaxRows(x) instead
	private static String getMax(OADataSourceJDBC ds, int max) {
		String str = "";
		if (max > 0) {
			DBMetaData dbmd = ds.getDBMetaData();
			if (OAString.isNotEmpty(dbmd.maxString)) {
				str = OAString.convert(dbmd.maxString, "?", (max+"")) + " ";
			}
		}
		return str;
	}
	*/

	/**
	 * Executes a passthrough SELECT using explicit WHERE and ORDER BY clauses.
	 *
	 * @param ds the JDBC data source
	 * @param clazz the class being selected
	 * @param queryWhere FROM/WHERE clause
	 * @param queryOrder ORDER BY clause
	 * @param max maximum number of rows
	 * @param bDirty {@code true} to allow dirty reads
	 * @return an iterator over the result set
	 */
	public static OADataSourceIterator selectPassthru(OADataSourceJDBC ds, Class clazz, String queryWhere, String queryOrder, int max,
			boolean bDirty) {
		Table table = ds.getDatabase().getTable(clazz);
		if (table == null) {
			return null;
		}

		QueryConverter qc = new QueryConverter(ds);
		String query = qc.getSelectColumns(clazz, bDirty);
		if (queryWhere != null && queryWhere.length() > 0) {
			query += " " + queryWhere;
		}
		if (queryOrder != null && queryOrder.length() > 0) {
			query += " ORDER BY " + queryOrder;
		}

		ResultSetIterator rsi;
		DataAccessObject dao = table.getDataAccessObject();
		if (!bDirty && dao != null) {
			rsi = new ResultSetIterator(ds, clazz, dao, "SELECT " + query, null, max);
		} else {
			Column[] columns = qc.getSelectColumnArray(clazz);
			rsi = new ResultSetIterator(ds, clazz, columns, "SELECT " + query, max);
			//was: rsi = new ResultSetIterator(ds, clazz, columns, "SELECT "+getMax(ds,max)+query, max);
		}
		rsi.setDirty(bDirty);
		return rsi;
	}

	/**
	 * Executes a SQL command using the provided JDBC data source.
	 * <p>
	 * Obtains a {@link java.sql.Statement} from the data source, executes the
	 * supplied command, and ensures the statement is released afterward.
	 * </p>
	 *
	 * @param ds the JDBC data source used to obtain and release the statement
	 * @param command the SQL command to execute
	 * @return {@code null} upon successful execution
	 * @throws RuntimeException if an exception occurs during execution, wrapping
	 *         the original exception
	 */
	public static Object execute(OADataSourceJDBC ds, String command) {
		// LOG.fine("command="+command);
		Statement st = null;
		try {
			st = ds.getStatement(command);
			st.execute(command);
			return null;
		} catch (Exception e) {
			throw new RuntimeException("OADataSourceJDBC.execute() " + command, e);
		} finally {
			if (st != null) {
				ds.releaseStatement(st);
			}
		}
	}

	// Note: queryWhere needs to begin with "FROM TABLENAME WHERE ..."
	/**
	 * Executes a passthrough COUNT query using the provided SQL fragment.
	 * <p>
	 * Builds a {@code SELECT COUNT(*)} statement, executes it using a JDBC
	 * {@link java.sql.Statement}, applies the {@code max} limit if specified,
	 * and returns the resulting count.
	 * </p>
	 *
	 * @param ds the JDBC data source used to execute the query
	 * @param query the SQL fragment appended after {@code SELECT COUNT(*)}
	 * @param max maximum count value to return
	 * @return the count result, limited by {@code max} if specified
	 * @throws RuntimeException if an error occurs during execution
	 */
	public static int countPassthru(OADataSourceJDBC ds, String query, int max) {
		String s = "SELECT COUNT(*) ";
		//was: String s = "SELECT "+getMax(ds, max)+"COUNT(*) ";
		if (query != null && query.length() > 0) {
			s += query;
		}
		// LOG.fine("sql="+s);
		Statement st = null;
		try {
			st = ds.getStatement(s);
			java.sql.ResultSet rs = st.executeQuery(s);
			rs.next();
			int x = rs.getInt(1);
			if (max > 0 && x > max) {
				x = max;
			}
			return x;
		} catch (Exception e) {
			throw new RuntimeException("OADataSourceJDBC.count() " + query, e);
		} finally {
			if (st != null) {
				ds.releaseStatement(st);
			}
		}

	}

	/**
	 * Counts rows for the specified class using a related where-object.
	 * <p>
	 * Delegates to the overloaded {@code count} method with additional parameters
	 * set to {@code null}.
	 * </p>
	 *
	 * @param ds the JDBC data source
	 * @param selectClass the class being counted
	 * @param whereObject the related where-object
	 * @param propertyFromWhereObject property linking the where-object
	 * @param max maximum count value to return
	 * @return the count result
	 */
	public static int count(OADataSourceJDBC ds, Class selectClass, Object whereObject, String propertyFromWhereObject, int max) {
		return count(ds, selectClass, whereObject, propertyFromWhereObject, null, null, null, max);
	}

	/**
	 * Counts rows for the specified class using a where-object and optional filters.
	 * <p>
	 * Builds a {@code SELECT COUNT(*)} SQL statement using the provided parameters,
	 * executes it, and returns the resulting count, limited by {@code max} if specified.
	 * </p>
	 *
	 * @param ds the JDBC data source
	 * @param selectClass the class being counted
	 * @param whereObject the related where-object
	 * @param propertyFromWhereObject property linking the where-object
	 * @param queryWhere additional WHERE clause
	 * @param args query parameter values
	 * @param extraWhere extra SQL conditions
	 * @param max maximum count value to return
	 * @return the count result
	 * @throws RuntimeException if execution fails
	 */
	public static int count(OADataSourceJDBC ds, Class selectClass,
			Object whereObject, String propertyFromWhereObject,
			String queryWhere, Object[] args,
			String extraWhere,
			int max) {
		if (whereObject instanceof OAObject) {
			if (((OAObject) whereObject).getNew()) {
				return 0;
			}
		}

		if (propertyFromWhereObject == null) {
			propertyFromWhereObject = "";
		}
		QueryConverter qc = new QueryConverter(ds);
		String s = qc.convertToSql(selectClass, whereObject, propertyFromWhereObject, queryWhere, args, extraWhere, "");

		s = "SELECT COUNT(*) " + s;
		//was: s = "SELECT "+getMax(ds, max)+"COUNT(*) " + s;
		// LOG.fine("selectClass="+selectClass.getName()+", whereObject="+whereObject+", extraWhere="+extraWhere+", propertyFromWhereObject="+propertyFromWhereObject+", sql="+s);

		Statement st = null;
		try {
			st = ds.getStatement(s);
			if (max > 0) {
				st.setMaxRows(max);
			}
			java.sql.ResultSet rs = st.executeQuery(s);
			rs.next();
			int x = rs.getInt(1);
			if (max > 0 && x > max) {
				x = max;
			}
			return x;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			try {
				if (max > 0) {
					st.setMaxRows(0);
				}
			} catch (Exception ex) {
			}
			;
			if (st != null) {
				ds.releaseStatement(st);
			}
		}
	}

	/**
	 * Counts rows for the specified class using a WHERE clause.
	 * <p>
	 * Delegates to the overloaded {@code count} method with no parameters array.
	 * </p>
	 *
	 * @param ds the JDBC data source
	 * @param clazz the class being counted
	 * @param queryWhere the WHERE clause
	 * @param max maximum count value to return
	 * @return the count result
	 */
	public static int count(OADataSourceJDBC ds, Class clazz, String queryWhere, int max) {
		return count(ds, clazz, queryWhere, (Object[]) null, max);
	}

	/**
	 * Counts rows for the specified class using a WHERE clause and a single parameter.
	 * <p>
	 * Wraps the parameter into an array and delegates to the array-based
	 * {@code count} method.
	 * </p>
	 *
	 * @param ds the JDBC data source
	 * @param clazz the class being counted
	 * @param queryWhere the WHERE clause
	 * @param param a single query parameter value
	 * @param max maximum count value to return
	 * @return the count result
	 */
	public static int count(OADataSourceJDBC ds, Class clazz, String queryWhere, Object param, int max) {
		Object[] params = null;
		if (param != null) {
			params = new Object[] { param };
		}
		return count(ds, clazz, queryWhere, params, max);
	}

	/**
	 * Counts rows for the specified class using a WHERE clause and parameters.
	 * <p>
	 * Builds and executes a {@code SELECT COUNT(*)} SQL statement using the
	 * {@link QueryConverter}, applies the {@code max} limit if specified,
	 * and returns the resulting count.
	 * </p>
	 *
	 * @param ds the JDBC data source
	 * @param clazz the class being counted
	 * @param queryWhere the WHERE clause
	 * @param params query parameter values
	 * @param max maximum count value to return
	 * @return the count result
	 * @throws RuntimeException if execution fails
	 */
	public static int count(OADataSourceJDBC ds, Class clazz, String queryWhere, Object[] params, int max) {
		QueryConverter qc = new QueryConverter(ds);

		String s = qc.convertToSql(clazz, queryWhere, params, "");
		s = "SELECT COUNT(*) " + s;
		//was: s = "SELECT "+getMax(ds,max)+"COUNT(*) " + s;
		// LOG.fine("selectClass="+clazz.getName()+", querWhere="+queryWhere+", sql="+s);

		Statement st = null;
		try {
			st = ds.getStatement(s);
			if (max > 0) {
				st.setMaxRows(max);
			}
			java.sql.ResultSet rs = st.executeQuery(s);
			rs.next();
			int x = rs.getInt(1);
			if (max > 0 && x > max) {
				x = max;
			}
			return x;
		} catch (Exception e) {
			throw new RuntimeException("OADataSourceJDBC.count() ", e);
		} finally {
			if (max > 0) {
				try {
					st.setMaxRows(0);
				} catch (Exception ex) {
				}
				;
			}
			if (st != null) {
				ds.releaseStatement(st);
			}
		}
	}

	/**
	 * Retrieves a BLOB property value for the specified object.
	 * <p>
	 * Selects the column corresponding to the given property using the object's
	 * primary key and returns its value as a byte array.
	 * </p>
	 *
	 * @param ds the JDBC data source
	 * @param whereObject the object whose property value is retrieved
	 * @param property the property name representing a BLOB column
	 * @return the BLOB value as a byte array, or {@code null} if not found
	 * @throws Exception if the table, column, or primary key cannot be resolved
	 */
	public static byte[] getPropertyBlobValue(OADataSourceJDBC ds, OAObject whereObject, String property) throws Exception {
		if (whereObject.getNew()) {
			return null;
		}
		if (property == null) {
			return null;
		}

		Class clazz = whereObject.getClass();
		Table table = ds.getDatabase().getTable(clazz);
		if (table == null) {
			throw new Exception("table not found for class=" + clazz + ", property=" + property);
		}
		QueryConverter qc = new QueryConverter(ds);

		Column[] cols = qc.getSelectColumnArray(clazz);
		String colName = "";
		String pkeyColName = "";
		String pkey = null;
		Column[] columns = null;
		for (Column c : cols) {
			if (property.equalsIgnoreCase(c.propertyName)) {
				colName = c.columnName;
				columns = new Column[] { c };
			} else if (c.primaryKey) {
				pkeyColName = c.columnName;
				pkey = whereObject.getPropertyAsString(c.propertyName);
			}
		}
		if (columns == null) {
			throw new Exception("column name not found for class=" + clazz + ", property=" + property);
		}
		if (pkey == null) {
			throw new Exception("pkey column not found for class=" + clazz + ", property=" + property);
		}

		String query = "SELECT " + colName;
		query += " FROM " + table.name + " WHERE " + pkeyColName + " = " + pkey;

		byte[] result = null;
		Statement statement = null;
		OATransaction trans = null;
		try {
			//trans = new OATransaction(java.sql.Connection.TRANSACTION_READ_COMMITTED);
			//trans.start();

			statement = ds.getStatement(query);
			ResultSet rs = statement.executeQuery(query);
			boolean b = rs.next();
			if (!b) {
				return null;
			}

			// 20211212 postgress (bytea) did not like getBlob logic, failed on reading long (size)
			result = rs.getBytes(1);
			/*
			Blob blob = rs.getBlob(1);
			if (blob != null) {
				result = blob.getBytes(1, (int) blob.length());
			}
			*/
			rs.close();
		} finally {
			ds.releaseStatement(statement);
			//trans.commit();
		}
		return result;
	}

	/**
	 * Selects entries from a many-to-many link table.
	 * <p>
	 * Resolves the link table and foreign keys for the specified link information,
	 * executes a SELECT query, and returns the resulting key pairs.
	 * </p>
	 *
	 * @param ds the JDBC data source
	 * @param linkInfo the link metadata describing the many-to-many relationship
	 * @return a list of {@link ManyToMany} key pairs, or {@code null} if not applicable
	 * @throws RuntimeException if execution fails
	 */
	public static ArrayList<ManyToMany> getManyToMany(OADataSourceJDBC ds, OALinkInfo linkInfo) {
		if (linkInfo == null) {
			return null;
		}

		OALinkInfo revLinkInfo = linkInfo.getReverseLinkInfo();

		if (linkInfo.getType() != OALinkInfo.MANY) {
			return null;
		}
		if (revLinkInfo.getType() != OALinkInfo.MANY) {
			return null;
		}

		Class classFrom = revLinkInfo.getToClass();
		Class classTo = linkInfo.getToClass();

		// Note: this assumes that fkeys are only one column

		DBMetaData dbmd = ds.getDBMetaData();
		Table linkTable = null;

		Table fromTable = ds.getDatabase().getTable(classFrom);
		if (fromTable == null) {
			return null;
		}
		Link[] fromTableLinks = fromTable.getLinks();
		if (fromTableLinks == null) {
			return null;
		}
		Column[] fromFKeys = null;

		for (int i = 0; i < fromTableLinks.length; i++) {
			if (!fromTableLinks[i].toTable.bLink) {
				continue;
			}
			if (!fromTableLinks[i].propertyName.equalsIgnoreCase(linkInfo.getName())) {
				continue;
			}
			linkTable = fromTableLinks[i].toTable;
			fromFKeys = fromTableLinks[i].fkeys;
			break;
		}
		if (linkTable == null) {
			return null;
		}
		if (fromFKeys == null) {
			return null;
		}

		fromTableLinks = linkTable.getLinks();
		if (fromTableLinks == null) {
			return null;
		}
		Column[] linkTableFromFKeys = null;
		for (int i = 0; i < fromTableLinks.length; i++) {
			if (fromTableLinks[i].toTable == fromTable) {
				linkTableFromFKeys = fromTableLinks[i].fkeys;
				break;
			}
		}
		if (linkTableFromFKeys == null) {
			return null;
		}

		Table toTable = ds.getDatabase().getTable(classTo);
		if (toTable == null) {
			return null;
		}
		Link[] toTableLinks = toTable.getLinks();
		if (toTableLinks == null) {
			return null;
		}
		Column[] toFKeys = null;

		for (int i = 0; i < toTableLinks.length; i++) {
			if (!toTableLinks[i].toTable.bLink) {
				continue;
			}
			if (!toTableLinks[i].propertyName.equalsIgnoreCase(revLinkInfo.getName())) {
				continue;
			}
			linkTable = toTableLinks[i].toTable;
			toFKeys = toTableLinks[i].fkeys;
			break;
		}
		if (toFKeys == null) {
			return null;
		}

		toTableLinks = linkTable.getLinks();
		if (toTableLinks == null) {
			return null;
		}
		Column[] linkTableToFKeys = null;
		for (int i = 0; i < toTableLinks.length; i++) {
			if (toTableLinks[i].toTable == toTable && linkTableFromFKeys != toTableLinks[i].fkeys) {
				linkTableToFKeys = toTableLinks[i].fkeys;
				break;
			}
		}
		if (linkTableToFKeys == null) {
			return null;
		}

		String query = "SELECT ";
		query += linkTableFromFKeys[0].columnName;
		query += ", " + linkTableToFKeys[0].columnName;
		query += " FROM " + linkTable.name;

		ArrayList<ManyToMany> al = null;
		Statement st = null;
		try {
			st = ds.getStatement(query);
			ResultSet rs = st.executeQuery(query);

			final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(classFrom);
			
			al = new ArrayList<>();
			while (rs.next()) {
				OAObjectKey ok1 = og.objectsInternal().callObjectKeyCreateObjectKey((Object) rs.getInt(1));
				OAObjectKey ok2 = og.objectsInternal().callObjectKeyCreateObjectKey((Object) rs.getInt(2));
				al.add(new ManyToMany(ok1, ok2));
			}
		} catch (Exception e) {
			throw new RuntimeException("OADataSourceJDBC.execute() " + query, e);
		} finally {
			if (st != null) {
				ds.releaseStatement(st);
			}
		}
		return al;
	}

}
