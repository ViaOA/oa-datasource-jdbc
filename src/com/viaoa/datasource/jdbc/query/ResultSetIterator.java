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
package com.viaoa.datasource.jdbc.query;

import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.viaoa.concurrent.OAThrottle;
import com.viaoa.converter.OAConv;
import com.viaoa.converter.OAConverter;
import com.viaoa.datasource.OADataSourceIterator;
import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datasource.jdbc.db.DataAccessObject;
import com.viaoa.datetime.OADate;
import com.viaoa.datetime.OADateTime;
import com.viaoa.datetime.OATime;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.sibling.OASiblingHelper;
import com.viaoa.hub.Hub;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.performance.OAPerformance;
import com.viaoa.reflect.OAReflect;
import com.viaoa.runtime.OARuntime;
import com.viaoa.runtime.OAThreadLocalService;
import com.viaoa.runtime.OAThreadService;
import com.viaoa.transaction.OATransaction;

/**
 * Streams database results into OAObjects using JDBC and OA metadata.
 * <p>
 * Executes SQL or prepared statements and lazily constructs objects from each row,
 * populating primitive and reference properties as defined in the corresponding
 * {@link com.viaoa.datasource.jdbc.db.Column} metadata.
 * <p>
 * Features:
 * <ul>
 *   <li>Supports both direct SELECT and two-phase queries (primary-key select + data fetch).</li>
 *   <li>Handles prepared statement arguments and JDBC type conversion.</li>
 *   <li>Integrates with {@link com.viaoa.object.OAObjectCacheDelegate} to prevent duplicates.</li>
 *   <li>Supports streaming large result sets with internal throttling and read-ahead buffering.</li>
 *   <li>Implements {@link com.viaoa.datasource.OADataSourceIterator} for OA query iteration.</li>
 * </ul>
 * Thread-safe for sequential iteration within a single JDBC context.
 */
public class ResultSetIterator implements OADataSourceIterator {
	private static Logger LOG = Logger.getLogger(ResultSetIterator.class.getName());

	/**
	 * The JDBC data source used to create statements, obtain connections,
	 * and access database metadata during iteration.
	 */
	OADataSourceJDBC ds;

	/**
	 * The OAObject class type that will be instantiated for each row in the
	 * result set.
	 */
	Class clazz;
	
	/**
	 * The primary SQL query used to retrieve data or primary keys from the
	 * underlying database.
	 */
	String query;
	
	/**
	 * JDBC statement used when executing a non-prepared SQL query.
	 */
	Statement statement;
	
	/**
	 * JDBC prepared statement used when the iterator is configured to run
	 * parameterized queries.
	 */
	PreparedStatement preparedStatement;
	
	/**
	 * Optional transaction context associated with the iteration, used for
	 * committing work once reading is complete.
	 */
	OATransaction transaction;
	
	/**
	 * The active JDBC result set containing retrieved rows for iteration.
	 */
	ResultSet rs;
	
	/**
	 * Column metadata describing how each result-set column maps to OAObject
	 * properties or foreign-key references.
	 */
	Column[] columns;
	
	/**
	 * Temporary working array for storing raw or converted column values for
	 * the current row.
	 */
	Object[] values;
	
	/**
	 * Internal metadata describing each column’s mapping state, including its
	 * primary-key position if applicable.
	 */
	ColumnInfo[] columnInfos;
	
	/**
	 * Indicates whether the iterator has additional rows available to read.
	 */
	volatile boolean bMore = false;
	
	/**
	 * Index of the last column required to fully construct a primary key,
	 * enabling cache lookups before object creation.
	 */
	int lastPkeyColumn; // last column needed to be able to create an ObjectKey, to do a cache lookup
	
	/**
	 * Optional maximum number of rows to read from the result set. A value of
	 * zero or less indicates no limit.
	 */
	int max;
	
	/**
	 * Counter tracking the number of objects returned so far from the
	 * iterator.
	 */
	int cnter;

	/**
	 * Secondary SQL query used when initial retrieval only yields primary-key
	 * values and a follow-up query is required to fetch full row data.
	 */
	String query2;
	
	/**
	 * JDBC statement used to execute the secondary query (query2) when
	 * two-phase selection logic is active.
	 */
	Statement statement2;
	
	/**
	 * Result set for the secondary query used to load full data rows when
	 * primary-key selection is performed separately.
	 */
	ResultSet rs2;
	
	/**
	 * Number of primary-key columns participating in object identity and used
	 * to determine when enough values have been read to construct an
	 * OAObjectKey.
	 */
	int idColumnCount;
	
	/**
	 * OAObject metadata describing ID properties, caching rules, and other
	 * structural information for the target class.
	 */
	OAObjectInfo oi;
	
	/**
	 * Temporary array holding primary-key values for the current row, used to
	 * construct an OAObjectKey for cache lookup or foreign-key assignment.
	 */
	Object[] pkeyValues;
	
	/**
	 * Indicates whether date values from the database include time
	 * information, as determined by JDBC metadata.
	 */
	boolean bDatesIncludeTime;
	
	/**
	 * Database-specific representations of boolean true and false values,
	 * used to convert numeric or DB-native flags to Java booleans.
	 */
	Object objectTrue, objectFalse;
	
	/**
	 * Flag indicating whether the iterator should force property assignment
	 * even for objects already present in the cache.
	 */
	boolean bDirty;
	
	/**
	 * Indicates that a SQL query is currently being executed. Used to control
	 * cancellation and closing behavior.
	 */
	volatile boolean bIsSelecting;
	
	/**
	 * Tracks whether the iterator’s initialization process has already been
	 * executed.
	 */
	volatile boolean bInit;
	
	/**
	 * Optional DAO used to construct OAObjects directly from result-set data,
	 * bypassing explicit column-by-column processing when available.
	 */
	DataAccessObject dataAccessObject;
	
	/**
	 * Buffer structure used by DataAccessObject to map result-set columns to
	 * object properties during direct object creation.
	 */
	DataAccessObject.ResultSetInfo resultSetInfo = new DataAccessObject.ResultSetInfo();
	
	/**
	 * Prepared-statement arguments supplied when executing a parameterized
	 * SQL query.
	 */
	Object[] arguments; // when using preparedStatement
	
	/**
	 * Indicates whether a prepared statement should be used instead of a
	 * standard SQL Statement.
	 */
	private boolean bUsePreparedStatement;

	/**
	 * Throttle used to regulate logging of long-running query operations,
	 * preventing excessive output while still reporting performance issues.
	 */
	public static final OAThrottle throttle = new OAThrottle(500);

	/**
	 * Returns the primary SQL query used by this iterator.
	 *
	 * @return the SQL query string.
	 */
	public String getQuery() {
		return query;
	}

	/**
	 * Returns the secondary SQL query used for two-phase primary-key/data
	 * retrieval.
	 *
	 * @return the secondary SQL query string, or null if not used.
	 */
	public String getQuery2() {
		return query2;
	}

	/**
	 * Holds metadata for a specific column in the result set, including a
	 * reference to the Column definition and its primary-key position within
	 * the object’s identity.
	 */
	class ColumnInfo {
		Column column;
		int pkeyPos = -1;
	}

	/**
	 * Creates a new iterator using an optional DataAccessObject and a
	 * two-phase query (primary key select plus data select).
	 *
	 * @param ds the JDBC data source.
	 * @param clazz the OAObject class type to be instantiated.
	 * @param dataAccessObject DAO used for direct object creation.
	 * @param query the primary SQL query.
	 * @param query2 secondary SQL query for full-row selection.
	 * @param max the maximum number of rows to read.
	 */
	public ResultSetIterator(OADataSourceJDBC ds, Class clazz, DataAccessObject dataAccessObject, String query, String query2, int max) {
		this(ds, clazz, null, query, query2, max, dataAccessObject);
	}

	/**
	 * Creates a new iterator that executes a prepared SQL statement using the
	 * given arguments.
	 *
	 * @param ds the JDBC data source.
	 * @param clazz the OAObject class type to be instantiated.
	 * @param dataAccessObject DAO for creating objects from result-set data.
	 * @param query the SQL query to prepare.
	 * @param arguments parameter values for the prepared statement.
	 */
	public ResultSetIterator(OADataSourceJDBC ds, Class clazz, DataAccessObject dataAccessObject, String query, Object[] arguments) {
		this.ds = ds;
		this.clazz = clazz;
		this.dataAccessObject = dataAccessObject;
		this.query = query;
		this.arguments = arguments;
		bUsePreparedStatement = true;
	}

	/**
	 * Creates a new iterator using explicit column metadata and a prepared
	 * statement.
	 *
	 * @param ds the JDBC data source.
	 * @param clazz the OAObject class type.
	 * @param columns metadata describing column/property mappings.
	 * @param query the SQL query to prepare.
	 * @param arguments parameter values for the prepared statement.
	 * @param max maximum number of rows to read.
	 */
	public ResultSetIterator(OADataSourceJDBC ds, Class clazz, Column[] columns, String query, Object[] arguments, int max) {
		this(ds, clazz, columns, query, null, max, null);
		this.arguments = arguments;
		bUsePreparedStatement = true;
	}

	/**
	 * Creates a new iterator using explicit column metadata and a standard
	 * non-prepared SQL query.
	 *
	 * @param ds the JDBC data source.
	 * @param clazz the OAObject class type.
	 * @param columns metadata for result-set to object mapping.
	 * @param query SQL query to execute.
	 * @param max maximum number of rows to fetch.
	 */
	public ResultSetIterator(OADataSourceJDBC ds, Class clazz, Column[] columns, String query, int max) {
		this(ds, clazz, columns, query, null, max, null);
	}

	/**
	 * Creates a new iterator using explicit column metadata and optional
	 * two-phase primary-key/data retrieval.
	 *
	 * @param ds JDBC data source.
	 * @param clazz OAObject class type.
	 * @param columns column metadata definitions.
	 * @param query primary SQL query.
	 * @param query2 secondary SQL query.
	 * @param max maximum number of rows to read.
	 */
	public ResultSetIterator(OADataSourceJDBC ds, Class clazz, Column[] columns, String query, String query2, int max) {
		this(ds, clazz, columns, query, query2, max, null);
	}

	/**
	 * Internal constructor shared by the public overloads. Initializes
	 * internal fields but does not execute any queries.
	 *
	 * @param ds the JDBC data source.
	 * @param clazz the OAObject class type.
	 * @param columns optional column metadata.
	 * @param query primary SQL query.
	 * @param query2 secondary SQL query.
	 * @param max maximum number of rows.
	 * @param dataAccessObject optional DAO for object construction.
	 */
	private ResultSetIterator(OADataSourceJDBC ds, Class clazz, Column[] columns, String query, String query2, int max,
			DataAccessObject dataAccessObject) {
		// LOG.fine("query="+query+", query2="+query2+", columns.length="+columns.length+", max="+max);
		this.ds = ds;
		this.clazz = clazz;
		this.columns = columns;
		this.query = query;
		this.query2 = query2;
		this.max = max;
		this.dataAccessObject = dataAccessObject;
	}

	/**
	 * Sets whether the iterator should treat objects as dirty, which forces
	 * properties to be reassigned even when an object is found in the cache.
	 *
	 * @param b true to enable dirty mode, false otherwise.
	 */
	public void setDirty(boolean b) {
		this.bDirty = b;
	}

	/**
	 * Returns whether the iterator is currently operating in dirty mode.
	 *
	 * @return true if dirty mode is enabled, false otherwise.
	 */
	public boolean getDirty() {
		return this.bDirty;
	}

	/**
	 * Performs one-time initialization of the iterator, including execution
	 * of the SQL query and setup of metadata used during row processing.
	 */
	protected synchronized void init() {
		if (bInit) {
			return;
		}
		bInit = true;

		long ts = System.currentTimeMillis();
		_init();
		long msDiff = System.currentTimeMillis() - ts;

		if (throttle.check() || msDiff > 3000) {
			String txt = throttle.getCheckCount() + ") ResultSetIterator: ";
			txt += msDiff + "ms";
			if (msDiff > 5000) {
				txt += " ALERT";
			}

			String s = query;
			int pos = s.toUpperCase().indexOf(" FROM ");
			if (pos > 0) {
				s = s.substring(pos + 1);
			}
			pos = s.toUpperCase().indexOf("PASSWORD");
			if (pos > 0) {
				s = s.substring(0, pos) + "****";
			}
			txt += " query=" + s;

			if (msDiff > 3000) {
				OAPerformance.LOG.fine(txt);
			}
			LOG.fine(txt);
			if (OAObject.getDebugMode()) {
				System.out.println(txt);
			}
		}
	}

	/**
	 * Internal implementation of {@link #init()} that prepares column
	 * metadata, configures statements, executes queries, and positions the
	 * result set at the first row.
	 */
	private void _init() {
		/*
		if ( (qqq%(DisplayMod*4)==0)) {
		    Vector v = OADataSource.getInfo();
		    for (int i=0; i<v.size(); i++) {
		        System.out.println("  "+v.elementAt(i));
		    }
		}
		*/
		// 20120227 add transaction
		//        transaction = new OATransaction(Connection.TRANSACTION_READ_COMMITTED);
		//        transaction.start();

		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
		this.oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(clazz);

		DBMetaData dbmd = ds.getDBMetaData();
		this.bDatesIncludeTime = dbmd.getDatesIncludeTime();
		this.objectTrue = dbmd.getObjectTrue();
		this.objectFalse = dbmd.getObjectFalse();

		String[] pkeys = this.oi.getIdProperties();
		if (dataAccessObject != null) {
			// no-op needed
			idColumnCount = (pkeys == null) ? 0 : pkeys.length;
		} else {
			this.values = new Object[columns.length];
			this.columnInfos = new ColumnInfo[columns.length];

			this.pkeyValues = new Object[pkeys.length];

			// create column infos
			for (int i = 0; i < columns.length; i++) {
				columnInfos[i] = new ColumnInfo();
				columnInfos[i].column = columns[i];
				if (columns[i].primaryKey) {
					idColumnCount++;
				}
				assert (columns[i].clazz != null);
				if (columns[i].propertyName == null) {
					assert (columns[i].fkeyLink != null);
					continue;
				}
				for (int j = 0; j < pkeys.length; j++) {
					if (pkeys[j].equalsIgnoreCase(columns[i].propertyName)) {
						columnInfos[i].pkeyPos = j;
						lastPkeyColumn = Math.max(lastPkeyColumn, i);
					}
				}
			}
		}

		rs = null;
		try {
			bIsSelecting = true;
			if (bUsePreparedStatement) {
				preparedStatement = ds.getConnectionPool().getPreparedStatement(query, false);
				for (int i = 0; arguments != null && i < arguments.length; i++) {
					// 20211206 need to convert argument to correct jdbc type
					Object arg = arguments[i];
					if (arg instanceof OADate) {
						arg = OAConv.convert(java.sql.Date.class, arg);
					} else if (arg instanceof OADateTime) {
						arg = OAConv.convert(java.sql.Timestamp.class, arg);
					} else if (arg instanceof OATime) {
						arg = OAConv.convert(java.sql.Time.class, arg);
					}
					preparedStatement.setObject(i + 1, arg);
				}
				preparedStatement.setMaxRows(Math.max(0, max));
				rs = preparedStatement.executeQuery();
			} else if (statement == null && ds != null) {

				// 20200526
				if (max > 0) {
					if (ds.getDBMetaData().supportsFetchFirst) {
						query = OAString.concat(query, "OFFSET 0 ROWS FETCH FIRST " + max + " ROWS ONLY");
					} else if (ds.getDBMetaData().supportsLimit) {
						query = OAString.concat(query, "LIMIT " + max);
					}
				}

				statement = ds.getStatement(query);
				statement.setMaxRows(Math.max(0, max));
				rs = statement.executeQuery(query);
			}

			bMore = rs != null && rs.next(); // goto first
			bIsSelecting = false;
			if (!bMore) {
				_close();
			}
		} catch (Exception e) {
			_close();
			throw new RuntimeException(e + ", query: " + query, e);
		} finally {
			bIsSelecting = false;
		}
	}

	/**
	 * Determines whether additional objects are available to iterate over.
	 * This includes prefetched rows in the read-ahead hub.
	 *
	 * @return true if another object can be returned, false otherwise.
	 */
	public boolean hasNext() {
		if (!bInit) {
			init();
		}
		return (bMore || hubReadAhead != null);
	}

	/**
	 * Hub used for read-ahead caching of OAObjects to support streaming and
	 * sibling-helper functionality.
	 */
	private Hub hubReadAhead;

	/**
	 * Tracks which objects were newly loaded during iteration so their
	 * afterLoad() method can be invoked once they are returned.
	 */
	private HashSet<UUID> hsObjectWasLoaded;
	
	/**
	 * Helper used to support sibling processing when objects are retrieved
	 * through the iterator.
	 */
	private OASiblingHelper siblingHelper;

	/**
	 * Returns the next OAObject from the iterator. Loads additional rows into
	 * the read-ahead buffer when needed and triggers afterLoad() for newly
	 * created objects.
	 *
	 * @return the next OAObject, or null if no more data exists.
	 */
	public synchronized Object next() {
		if (!bInit) {
			init();
		}
		if (!bMore && hubReadAhead == null) {
			return null;
		}

		if (hubReadAhead == null) {
			hubReadAhead = new Hub();
			hsObjectWasLoaded = new HashSet<>(25, .75f);
		}

		hubReadAhead.remove(0); // remove last one that was returned from next().  It stayed in hubReadAhead in case getSiblings is called
		for (int i = hubReadAhead.size(); bMore && i < 100; i++) {
			_next();
		}

		OAObject obj = (OAObject) hubReadAhead.getAt(0);
		if (hsObjectWasLoaded.remove(obj.getGuid())) {
			if (siblingHelper == null) {
				siblingHelper = new OASiblingHelper(this.hubReadAhead);
			}
			OAThreadLocalService srvcThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
			boolean bx = srvcThreadLocal.addSiblingHelper(siblingHelper);
			try {
				obj.afterLoad();
			} finally {
				if (bx) {
					srvcThreadLocal.removeSiblingHelper(siblingHelper);
				}
			}
		}
		if (!bMore && hubReadAhead.size() == 1) {
			close();
		}
		return obj;
	}

	/**
	 * Returns the sibling helper used for managing related objects when
	 * iterating through results.
	 *
	 * @return the active OASiblingHelper instance or null.
	 */
	@Override
	public OASiblingHelper getSiblingHelper() {
		return siblingHelper;
	}

	/**
	 * Internal method that reads the next result-set row, constructs or
	 * updates the corresponding OAObject, manages caching behavior, and adds
	 * the object to the read-ahead hub.
	 *
	 * @return true if another row was successfully processed, false otherwise.
	 */
	protected boolean _next() {
		if (!bInit) {
			init();
		}
		if (rs == null) {
			return false;
		}
		if (max > 0 && cnter > max) {
			_close();
			return false;
		}

		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
		
		boolean bDataSourceLoadingObject = true;
		OAObject oaObject = null;
		boolean bLoadedObject = false;
		boolean bSetChangedAndNew = false;
		final OAThreadLocalService srvcThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();
		try {
			ResultSet resultSet = rs;
			if (query2 != null) { // need to do a seperate select to get data for each row
				if (statement2 == null && ds != null) {
					statement2 = ds.getStatement(query);
				}
				for (; bMore;) {
					String newQuery = query2;
					int pos = 0;
					for (int i = 0; i < idColumnCount; i++) {
						Object obj = rs.getObject(i + 1);
						String s;
						if (rs.wasNull()) {
							s = null;
						} else {
							s = OAConverter.toString(obj);
						}
						pos = newQuery.indexOf('?', pos);
						if (pos >= 0) {
							newQuery = newQuery.substring(0, pos) + s + newQuery.substring(pos + 1);
							if (s == null) {
								pos += 4;
							} else {
								pos += s.length();
							}
						} else {
							throw new RuntimeException("parameter mismatch in query " + query2);
						}
					}
					statement2.setMaxRows(0);
					rs2 = statement2.executeQuery(newQuery);
					if (rs2.next()) {
						resultSet = rs2;
						break;
					}
					bMore = rs.next(); // goto next
					rs2.close();
					if (!bMore) {
						return false;
					}
				}
			}

			// 20221219 so that validation checks are not done (ex: unique values)
			srvcThreadLocal.setLoading(true);
			/* was
			if (!bDirty) {
				OARuntime.threadLocals().setLoading(true);
			} else {
				bDataSourceLoadingObject = false;
			}
			*/

			if (!bDirty && dataAccessObject != null) {
				resultSetInfo.reset(resultSet);
				oaObject = dataAccessObject.getObject(resultSetInfo);
				bLoadedObject = !resultSetInfo.getFoundInCache();
				bSetChangedAndNew = true;

				if (bLoadedObject) {
					OAObject objx = (OAObject) og.objectsInternal().callObjectCacheAdd(oaObject, false, true);
					if (objx != oaObject) {
						oaObject = objx;
					}
				}
			} else {
				for (int i = 0; i < columnInfos.length; i++) {
					if (columnInfos[i].column.clazz.equals(String.class)) {
						values[i] = resultSet.getString(i + 1);
					} else if (columnInfos[i].column.clazz.equals(byte[].class)) {

						// 20220430 postgress (bytea) did not like getBlob logic, failed on reading long (size)
						values[i] = rs.getBytes(i + 1);

						/*was
						Blob blob = resultSet.getBlob(i + 1);
						if (blob != null) {
							values[i] = blob.getBytes(1, (int) blob.length());
						} else {
							values[i] = null;
						}
						*/
					} else {
						values[i] = resultSet.getObject(i + 1);
						if (values[i] == null) {
						} else if (resultSet.wasNull()) {
							values[i] = null;
						} else {
							values[i] = convert(columnInfos[i].column.clazz, values[i]);
						}
					}
					if (columnInfos[i].pkeyPos >= 0) {
						pkeyValues[columnInfos[i].pkeyPos] = values[i];
						if (i == lastPkeyColumn) {
							// try to find existing object
							oaObject = (OAObject) og.objectsInternal().callObjectCacheGet(clazz, new OAObjectKey(pkeyValues));
							if (oaObject != null && !bDirty) {
								break;
							}
						}
					}
				}

				if (oaObject == null || bDirty) {
					boolean bNew;
					if (oaObject == null) {
						bNew = true;
						oaObject = (OAObject) og.objectsInternal().callObjectReflectCreateNewObject(clazz);
					} else {
						bNew = false;
					}

					for (int i = 0; i < columns.length; i++) {
						if (!bNew && columnInfos[i].pkeyPos >= 0) {
							continue;
						}
						if (columnInfos[i].pkeyPos >= 0 || columns[i].fkeyLink == null) {
							try {
								oaObject.setProperty(columns[i].propertyName, values[i]);
							} catch (Exception e) {
								if (bNew && columnInfos[i].pkeyPos >= 0) {
									OAObject objx = (OAObject) og.objectsInternal().callObjectCacheGet(clazz, new OAObjectKey(pkeyValues));
									if (objx != null) {
										LOG.log(Level.WARNING, "Error while setting property " + columns[i].propertyName
												+ ", object has been found in cache, so everything is good", e);
										oaObject = objx;
										bNew = false;
										if (!bDirty) {
											break;
										}
									} else {
										LOG.log(Level.WARNING, "Error while setting property " + columns[i].propertyName
												+ ", NOT found in cache as hoped :(  will continue anyway", e);
									}
								} else {
									LOG.log(Level.WARNING,
											"Error while setting property " + columns[i].propertyName + ", will continue anyway", e);
								}
							}
						} else {
							// fkey
							if (columns[i].fkeyLink.fkeys.length == 1) {
								oaObject.setProperty(columns[i].fkeyLink.propertyName, values[i]);
								continue;
							}

							if (columns[i].fkeyLinkPos > 0) {
								continue; // already loaded (in next code)
							}
							Object[] ids = new Object[columns[i].fkeyLink.fkeys.length];
							for (int j = i; j < columns.length; j++) {
								if (columns[j].fkeyLink == columns[i].fkeyLink) {
									ids[columns[j].fkeyLinkPos] = values[j];
								}
							}
							oaObject.setProperty(columns[i].fkeyLink.propertyName, new OAObjectKey(ids));
						}
					}

					if (bNew && oi.getAddToCache()) { // 20110731 add to cache, OAThreadLocal.SkipObjectInitialize
						oaObject = (OAObject) og.objectsInternal().callObjectCacheAdd(oaObject, false, true);
					}

					og.objectsInternal().callObjectSetNew(oaObject, false);
					oaObject.setChanged(false);
					bLoadedObject = true;
					bSetChangedAndNew = true;
				}
			}

			++cnter;

			if (bDataSourceLoadingObject) {
				srvcThreadLocal.setLoading(false);
				bDataSourceLoadingObject = false;
			}

			if (bLoadedObject) {
				hsObjectWasLoaded.add(oaObject.getGuid());
			}

			if (rs != null) {
				bMore = rs.next(); // goto next
				if (!bMore) {
					_close();
				}
			}
			if (hubReadAhead != null) {
				hubReadAhead.add(oaObject);
			}

			return true;
		} catch (Exception e) {
			String s = String.format(	"Exception in next(), thread=%s, query=%s, bClosed=%b", Thread.currentThread().getName(), query,
										bClosed);
			LOG.log(Level.WARNING, s, e);
			throw new RuntimeException(e);
		} finally {
			if (bLoadedObject && !bSetChangedAndNew && oaObject != null) {
				og.objectsInternal().callObjectSetNew(oaObject, false);
				oaObject.setChanged(false);
			}
			if (bDataSourceLoadingObject) {
				srvcThreadLocal.setLoading(false);
			}
		}
	}

	/**
	 * Indicates whether the iterator has been fully closed and can no longer
	 * be used for reading data.
	 */
	private boolean bClosed;

	// part of iterator interface
	/**
	 * Part of the iterator interface; removes the current element. For this
	 * implementation, it simply triggers resource cleanup.
	 */
	public void remove() {
		_close();
	}

	/**
	 * Ensures that iterator resources are closed during garbage collection
	 * if the user did not explicitly call close().
	 */
	public void finalize() throws Throwable {
		super.finalize();
		close();
	}

	/**
	 * Closes the iterator and releases associated resources, including hubs,
	 * statements, result sets, and sibling helpers.
	 */
	public synchronized void close() {
		if (hubReadAhead != null) {
			hubReadAhead.clear();
			hubReadAhead = null;
		}
		if (siblingHelper != null) {
			siblingHelper = null;
		}
		bClosed = true;
		bMore = false;
		_close();
	}

	/**
	 * Performs low-level cleanup during close(), including canceling in-flight
	 * queries, closing result sets, committing transactions, and releasing
	 * statements back to the data source.
	 */
	protected void _close() {
		boolean b = false;
		try {
			if (bIsSelecting) {
				try {
					if (statement != null) {
						statement.cancel();
					}
					if (statement2 != null) {
						statement2.cancel();
					}
					if (preparedStatement != null) {
						preparedStatement.cancel();
					}
				} catch (Exception exx) {
					int xx = 4;
					xx++;
				}
			}

			if (rs != null) {
				rs.close();
				rs = null;
			}
			if (rs2 != null) {
				rs2.close();
				rs2 = null;
			}
			if (transaction != null) {
				transaction.commit();
			}

		} catch (Exception e) {
			// throw new OADataSourceException(OADataSourceJDBC.this, "OADataSource.getStatement() "+e);
		} finally {
			rs = null;
			rs2 = null;
			bMore = false;
			if (ds != null) {
				if (preparedStatement != null) { // 20121013
					ds.getConnectionPool().releasePreparedStatement(preparedStatement, true);
				} else {
					ds.releaseStatement(statement);
					if (statement2 != null) {
						ds.releaseStatement(statement2);
					}
				}
			}
			statement = null;
			statement2 = null;
			transaction = null;
			preparedStatement = null;
		}
	}

	/**
	 * Converts a raw database value to the expected Java type, handling JDBC
	 * types, numeric conversions, date/time formats, booleans, and byte-array
	 * mappings as needed.
	 *
	 * @param paramType expected target type.
	 * @param obj the database value to convert.
	 * @return converted value appropriate for the property type.
	 * @throws Exception if conversion fails.
	 */
	private Object convert(Class paramType, Object obj) throws Exception {
		if (obj == null) {
			return null;
		}
		if (obj.getClass().equals(paramType)) {
			return obj;
		}

		if (obj instanceof Clob) {
			obj = ((Clob) obj).getSubString(1, (int) ((Clob) obj).length());
		} else if (obj.getClass().isArray()) {
			// 2006/06/01
			Class c = OAReflect.getClassWrapper(paramType);
			if (Number.class.isAssignableFrom(c)) {
				obj = new java.math.BigInteger((byte[]) obj);
			} else if (java.util.Date.class.isAssignableFrom(paramType)) {
				obj = new java.util.Date(new java.math.BigInteger((byte[]) obj).longValue());
			} else if (paramType.equals(String.class)) { // 2006/11/08
				obj = new String((byte[]) obj);
			}
		}

		if (obj instanceof String) {
			String s = (String) obj;
			String fmt = null;
			if (paramType.equals(String.class)) {
				obj = repairSingleQuotes((String) obj);
			} else if (paramType.equals(int.class)) {
				obj = Integer.valueOf(s);
			} else if (paramType.equals(double.class)) {
				obj = Double.valueOf(s);
			} else if (paramType.equals(long.class)) {
				obj = Long.valueOf(s);
			} else if (paramType.equals(short.class)) {
				obj = Short.valueOf(s);
			} else if (paramType.equals(float.class)) {
				obj = Float.valueOf(s);
			} else if (paramType.equals(char.class)) {
				obj = Character.valueOf(s.charAt(0));
			} else {
				if (java.util.Date.class.isAssignableFrom(paramType)) {
					if (bDatesIncludeTime) {
						fmt = "yyyy-MM-dd hh:mm:ss.SSS"; // 1999-11-21 14:21:53.123
					} else {
						fmt = "yyyy-MM-dd"; // 1999-11-21
						if (paramType.equals(Time.class)) {
							fmt = "hh:mm:ss.SSS"; // 14:21:53
						}
					}
				} else {
					if (bDatesIncludeTime) {
						fmt = "yyyy-MM-dd hh:mm:ss.SSS"; // 1999-11-21 14:21:53.123
					} else {
						if (paramType.equals(OADate.class)) {
							fmt = "yyyy-MM-dd";
						} else if (paramType.equals(OATime.class)) {
							fmt = "hh:mm:ss.SSS";
						} else if (paramType.equals(OADateTime.class)) {
							fmt = "yyyy-MM-dd hh:mm:ss.SSS";
						}
					}
				}
				obj = OAConverter.convert(paramType, (String) obj, fmt);
			}
		} else if (obj instanceof Number) {
			Number num = (Number) obj;
			if (paramType.equals(int.class)) {
				obj = Integer.valueOf(num.intValue());
			} else if (paramType.equals(boolean.class)) {
				obj = Boolean.valueOf(num.intValue() != 0);
			} else if (paramType.equals(double.class)) {
				obj = Double.valueOf(num.doubleValue());
			} else if (paramType.equals(String.class)) {
				obj = num.toString();
			} else if (paramType.equals(long.class)) {
				obj = Long.valueOf(num.longValue());
			} else if (paramType.equals(short.class)) {
				obj = Short.valueOf(num.shortValue());
			} else if (paramType.equals(float.class)) {
				obj = Float.valueOf(num.floatValue());
			} else if (paramType.equals(char.class)) {
				obj = Character.valueOf((char) num.shortValue());
			} else if (paramType.equals(java.awt.Color.class)) {
				obj = new java.awt.Color(num.intValue());
			}
		} else if (obj instanceof Double && paramType.equals(float.class)) {
			obj = new Float(((Double) obj).floatValue());
		} else if (obj instanceof java.util.Date) {
			if (paramType.equals(Time.class)) {
				obj = new Time(((java.util.Date) obj).getTime());
			} else if (paramType.equals(java.sql.Timestamp.class)) {
				obj = new Timestamp(((java.util.Date) obj).getTime());
			} else if (paramType.equals(OADate.class)) {
				obj = new OADate((java.util.Date) obj);
			} else if (paramType.equals(OATime.class)) {
				obj = new OATime((java.util.Date) obj);
			} else if (paramType.equals(OADateTime.class)) {
				obj = new OADateTime((java.util.Date) obj); // 2006/11/08
			}
		} else if (obj instanceof Boolean) {
			boolean b = ((Boolean) obj).booleanValue();
			if (paramType.equals(boolean.class)) {
				;
			} else if (paramType.equals(int.class)) {
				obj = Integer.valueOf(b ? 1 : 0);
			} else if (paramType.equals(double.class)) {
				obj = Double.valueOf(b ? 1.0 : 0.0);
			} else if (paramType.equals(String.class)) {
				obj = obj.toString();
			} else if (paramType.equals(long.class)) {
				obj = Long.valueOf((long) (b ? 1 : 0));
			} else if (paramType.equals(short.class)) {
				obj = Short.valueOf((short) (b ? 1 : 0));
			} else if (paramType.equals(float.class)) {
				obj = Float.valueOf((float) (b ? 1.0f : 0.0f));
			} else if (paramType.equals(char.class)) {
				obj = Character.valueOf((char) (b ? '1' : '0'));
			}
		}

		if (paramType.equals(boolean.class)) {
			if (!(obj instanceof Boolean)) {
				if (objectTrue == null || objectFalse == null) {
					if (obj instanceof Number) {
						new Boolean(((Number) obj).intValue() != 0);
						//else throw new OADataSourceException(OADataSourceJDBC.this,"ResultSetIterator.next() "+" method "+method.getName()+" uses a boolean and database stores data as "+obj.getClass());
					}
				} else {
					if (obj.equals(objectTrue)) {
						obj = Boolean.TRUE;
					} else if (obj.equals(objectFalse)) {
						obj = Boolean.FALSE;
					} else {
						// throw new OADataSourceException(OADataSourceJDBC.this,"ResultSetIterator.next() "+" method "+method.getName()+" cant convert "+obj+" to a boolean, it does not match objectTrue or objectFalse values");
					}
				}
			}
		}
		return obj;
	}

	/**
	 * Repairs single-quote characters in string values when needed. Currently
	 * implemented as a passthrough.
	 *
	 * @param value input string.
	 * @return the repaired string.
	 */
	protected String repairSingleQuotes(String value) {
		return value;
	}

}
