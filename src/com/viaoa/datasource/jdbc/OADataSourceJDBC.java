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
package com.viaoa.datasource.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.viaoa.datasource.OADataSource;
import com.viaoa.datasource.OADataSourceIterator;
import com.viaoa.datasource.jdbc.connection.ConnectionPool;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datasource.jdbc.db.Database;
import com.viaoa.datasource.jdbc.db.ManyToMany;
import com.viaoa.datasource.jdbc.db.Table;
import com.viaoa.datasource.jdbc.delegate.AutonumberDelegate;
import com.viaoa.datasource.jdbc.delegate.DBMetaDataDelegate;
import com.viaoa.datasource.jdbc.delegate.Delegate;
import com.viaoa.datasource.jdbc.delegate.DeleteDelegate;
import com.viaoa.datasource.jdbc.delegate.InsertDelegate;
import com.viaoa.datasource.jdbc.delegate.SelectDelegate;
import com.viaoa.datasource.jdbc.delegate.UpdateDelegate;
import com.viaoa.datasource.jdbc.delegate.VerifyDelegate;
import com.viaoa.filter.OAFilter;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectKeyService;
import com.viaoa.lang.OAArray;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.path.OAPath;
import com.viaoa.runtime.OARuntime;

/**
 * JDBC-based implementation of {@link com.viaoa.datasource.OADataSource}.
 * <p>
 * {@code OADataSourceJDBC} provides full CRUD and query support for relational
 * databases using standard JDBC. It converts OA object and filter operations
 * into prepared SQL statements, manages connections, and maintains
 * schema-awareness for primary and foreign keys.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Full CRUD operations mapped to JDBC prepared statements.</li>
 *   <li>Automatic translation of object queries into SQL WHERE clauses.</li>
 *   <li>Lightweight connection pool with transaction support.</li>
 *   <li>Automatic detection of primary/foreign keys via database metadata.</li>
 *   <li>Configurable SQL dialects and column quoting strategies.</li>
 *   <li>Supports batch operations, auto-generated keys, and GUID/PK mapping.</li>
 * </ul>
 *
 * <h2>Design Notes</h2>
 * <ul>
 *   <li>Extends {@link com.viaoa.datasource.OADataSource} and implements
 *       {@link com.viaoa.datasource.OADataSourceInterface}.</li>
 *   <li>Delegates query building to {@link com.viaoa.datasource.jdbc.OASelectJDBC}.</li>
 *   <li>Uses {@link com.viaoa.datasource.jdbc.OAConnectionPool} for efficient reuse.</li>
 * </ul>
 *
 * @see com.viaoa.datasource.OADataSource
 * @see com.viaoa.datasource.jdbc.OASelectJDBC
 * @see com.viaoa.datasource.jdbc.OAConnectionPool
 */
public class OADataSourceJDBC extends OADataSource {

	private static Logger LOG = Logger.getLogger(OADataSourceJDBC.class.getName());

	/**
	 * Database metadata object describing schema details such as tables, columns,
	 * primary keys, and database-specific capabilities (e.g., auto-assign support).
	 */
	protected DBMetaData dbmd;

	/**
	 * The OA database mapping representing tables, relationships, and column
	 * configurations mapped to OA classes and properties.
	 */
	protected Database database;

	/**
	 * Connection pool used to manage creation, reuse, and lifecycle of JDBC
	 * connections, statements, and prepared statements for this datasource.
	 */
	protected ConnectionPool connectionPool;

	/**
	 * Constructs a new JDBC datasource bound to the specified database mapping
	 * and metadata. Initializes a {@link ConnectionPool} using the metadata.
	 *
	 * @param database the database mapping used for class/table resolution
	 * @param dbmd     the metadata describing schema and JDBC capabilities
	 */
	public OADataSourceJDBC(Database database, DBMetaData dbmd) {
		this.database = database;
		this.dbmd = dbmd;
		connectionPool = new ConnectionPool(dbmd);
	}

	/**
	 * Returns the {@link Database} mapping object used to translate OA classes
	 * into relational tables.
	 *
	 * @return the database mapping
	 */
	public Database getDatabase() {
		return database;
	}

	/**
	 * Returns the {@link DBMetaData} object containing database schema and
	 * capability information.
	 *
	 * @return the metadata for this datasource
	 */
	public DBMetaData getDBMetaData() {
		return dbmd;
	}

	/**
	 * Assigns a new {@link DBMetaData} instance to this datasource.
	 *
	 * @param dbmd the metadata object to use
	 */
	public void setDBMetaData(DBMetaData dbmd) {
		this.dbmd = dbmd;
	}

	/**
	 * Returns the connection pool used by this datasource for managing JDBC
	 * connections and statements.
	 *
	 * @return the connection pool
	 */
	public ConnectionPool getConnectionPool() {
		return connectionPool;
	}

	/**
	 * Indicates that this datasource supports full storage operations,
	 * including insert, update, delete, and select.
	 *
	 * @return true
	 */
	public @Override boolean supportsStorage() {
		return true;
	}

	/**
	 * Checks whether the underlying database is reachable by querying the
	 * connection pool. Logs and returns false if an exception occurs.
	 *
	 * @return true if the datasource is available
	 */
	public @Override boolean isAvailable() {
		boolean b = true;
		try {
			return connectionPool.isDatabaseAvailable();
		} catch (Exception e) {
			System.out.println("OADataSourceJDBC.isAvailable error: " + e);
			return false;
		}
	}

	/**
	 * Indicates that identifier values are not allowed to change after assignment.
	 *
	 * @return false
	 */
	public @Override boolean getAllowIdChange() {
		return false;
	}

	/**
	 * Determines whether the specified class has a corresponding table in the
	 * database mapping.
	 *
	 * @param clazz  the class to test
	 * @param filter unused for JDBC datasources
	 * @return true if the class is mapped to a table
	 */
	public @Override boolean isClassSupported(Class clazz, OAFilter filter) {
		boolean b = (database.getTable(clazz) != null);
		return b;
	}

	/**
	 * Adds datasource information to the provided vector by delegating to the
	 * connection pool.
	 *
	 * @param vec the vector to populate with datasource information
	 */
	public void getInfo(Vector vec) {
		connectionPool.getInfo(vec);
	}

	/**
	 * Assigns an identifier value for the specified object if auto-assignment
	 * on create is enabled. Wraps the internal assignment call with assigning-ID
	 * guards to prevent recursive property updates.
	 *
	 * @param object the object whose ID should be assigned
	 */
	public @Override void assignId(OAObject object) {
		if (!bAssignNumberOnCreate) {
			return;
		}
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object);
		try {
			og.objectsInternal().callObjectDSSetAssigningId(object, true);
			_assignId(object);
		} finally {
			og.objectsInternal().callObjectDSSetAssigningId(object, false);
		}
	}

	/**
	 * Internal implementation used to assign autonumber values for objects whose
	 * primary key columns support automatic number assignment. Walks up the class
	 * hierarchy and assigns values for each table that has a primary key column
	 * configured for next-number assignment.
	 *
	 * @param object the object receiving an assigned ID
	 */
	private void _assignId(OAObject object) {
		if (!dbmd.supportsAutoAssign) {
			return;
		}
		Class clazz = object.getClass();
		for (;;) {
			Table table = database.getTable(clazz);
			if (table == null) {
				return;
			}

			Column[] columns = table.getColumns();
			for (int i = 0; columns != null && i < columns.length; i++) {
				Column column = columns[i];
				if (column.primaryKey && column.assignNextNumber) {
					AutonumberDelegate.assignNumber(this, object, table, column);
					break;
				}
			}
			clazz = clazz.getSuperclass();
			if (clazz == null || clazz.equals(OAObject.class)) {
				break;
			}
		}
	}

	/**
	 * Sets the next autonumber value for the specified class by delegating
	 * to {@link AutonumberDelegate#setNextNumber}.
	 *
	 * @param c               the class whose table sequence will be updated
	 * @param nextNumberToUse the next number to assign
	 */
	public void setNextNumber(Class c, int nextNumberToUse) {
		Table table = database.getTable(c);
		AutonumberDelegate.setNextNumber(this, table, nextNumberToUse);
	}

	/**
	 * Updates the specified object in the database by delegating to
	 * {@link #_update}. Batch/transaction handling code is present but commented
	 * out, leaving this method as a direct call to the internal update routine.
	 *
	 * @param object            the object to update
	 * @param includeProperties optional list of properties to include
	 * @param excludeProperties optional list of properties to exclude
	 */
	public @Override void update(OAObject object, String[] includeProperties, String[] excludeProperties) {
		// 20221206
		_update(object, includeProperties, excludeProperties);
		/*was:
		if (!isAllowingBatch() && !isInTransaction()) {
			OATransaction trans = new OATransaction(java.sql.Connection.TRANSACTION_READ_COMMITTED);
			trans.setUseBatch(true);
			trans.start();
			try {
				_update(object, includeProperties, excludeProperties);
				trans.commit();
			} catch (RuntimeException e) {
				trans.rollback();
				throw e;
			}
		} else {
			_update(object, includeProperties, excludeProperties);
		}
		*/
	}

	/**
	 * Internal update implementation. Logs key information about the object and
	 * delegates the actual persistence operation to {@link UpdateDelegate#update}.
	 *
	 * @param object            the object to update
	 * @param includeProperties properties to include
	 * @param excludeProperties properties to exclude
	 */
	protected void _update(OAObject object, String[] includeProperties, String[] excludeProperties) {
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object);
		OAObjectKey key = og.objectsInternal().callObjectKeyGetKey(object);
		LOG.finer("object=" + object.getClass() + ", key=" + key);
		UpdateDelegate.update(this, object, includeProperties, excludeProperties);
	}

	/**
	 * Inserts the specified object into the database by delegating to
	 * {@link #_insert}. Batch/transaction logic exists but is commented out,
	 * leaving this method as a direct insert call.
	 *
	 * @param object the object to insert
	 */
	public @Override void insert(OAObject object) {
		// 20221206
		_insert(object);
		/*was
		if (!isAllowingBatch() && !isInTransaction()) {
			OATransaction trans = new OATransaction(java.sql.Connection.TRANSACTION_READ_COMMITTED);
			trans.setUseBatch(true);
			trans.start();
			try {
				_insert(object);
				trans.commit();
			} catch (RuntimeException e) {
				trans.rollback();
				throw e;
			}
		} else {
			_insert(object);
		}
		*/
	}

	/**
	 * Internal insert implementation. Logs the object's class, key, and new-state
	 * status, then delegates the actual persistence logic to
	 * {@link InsertDelegate#insert}.
	 *
	 * @param object the object to insert
	 */
	protected void _insert(OAObject object) {
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object);
		OAObjectKey key = og.objectsInternal().callObjectKeyGetKey(object);
		LOG.finer("object=" + object.getClass() + ", key=" + key + ", isNew=" + object.isNew());
		InsertDelegate.insert(this, object);
	}

	/**
	 * Inserts the specified object without processing any reference properties.
	 * Logs diagnostic key information and delegates to
	 * {@link InsertDelegate#insertWithoutReferences}.
	 *
	 * @param obj the object to insert without references
	 */
	public @Override void insertWithoutReferences(OAObject obj) {
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(obj);
		OAObjectKey key = og.objectsInternal().callObjectKeyGetKey(obj);
		LOG.fine("object=" + obj.getClass() + ", key=" + key + ", isNew=" + obj.isNew());
		InsertDelegate.insertWithoutReferences(this, obj);
	}

	/**
	 * Deletes the specified object from the database. Logs object identity
	 * information and delegates to {@link DeleteDelegate#delete}.
	 *
	 * @param object the object to delete
	 */
	public @Override void delete(OAObject object) {
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object);
		OAObjectKey key = og.objectsInternal().callObjectKeyGetKey(object);
		LOG.fine("object=" + object.getClass().getSimpleName() + ", key=" + key);
		DeleteDelegate.delete(this, object);
	}

	/**
	 * Deletes all rows mapped to the specified class. This operation is not
	 * implemented for JDBC datasources due to potential data-loss risks and
	 * always throws a {@link RuntimeException}.
	 *
	 * @param c the class whose objects would be deleted
	 * @throws RuntimeException always thrown, as delete-all is not supported
	 */
	public @Override void deleteAll(Class c) {
		LOG.fine("object=" + c.getSimpleName());
		// could be dangerous
		throw new RuntimeException("OADataSource.deleteAll(class) not yet implemented for OADataSourceJDBC - could be dangerous :)");
	}

	/**
	 * Updates a many-to-many relationship for the specified master object.
	 * Logs identifying information and delegates to
	 * {@link UpdateDelegate#updateMany2ManyLinks}.
	 *
	 * @param masterObject   the master object
	 * @param adds           objects to add to the link table
	 * @param removes        objects to remove from the link table
	 * @param propFromMaster the property defining the relationship
	 */
	public @Override void updateMany2ManyLinks(OAObject masterObject, OAObject[] adds, OAObject[] removes, String propFromMaster) {
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(masterObject);
		OAObjectKey key = og.objectsInternal().callObjectKeyGetKey(masterObject);
		LOG.finer("object=" + masterObject.getClass().getSimpleName() + ", key=" + key);
		UpdateDelegate.updateMany2ManyLinks(this, masterObject, adds, removes, propFromMaster);
	}

	/**
	 * Determines whether this datasource will automatically create a value for
	 * the specified property before saving the object. Only true when the
	 * property maps to a primary-key column configured for next-number assignment.
	 *
	 * @param object       the object being evaluated
	 * @param propertyName the name of the property
	 * @return true if a next-number assignment will occur
	 */
	public @Override boolean willCreatePropertyValue(OAObject object, String propertyName) {
		if (object == null) {
			return false;
		}
		Class clazz = object.getClass();
		if (propertyName == null) {
			return false;
		}
		Table table = database.getTable(clazz);
		if (table == null) {
			return false;
		}

		Column[] columns = table.getColumns();
		for (int i = 0; columns != null && i < columns.length; i++) {
			Column column = columns[i];
			if (propertyName.equalsIgnoreCase(column.propertyName)) {
				if (column.primaryKey && column.assignNextNumber) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Performs a SELECT query using JDBC, delegating to {@link SelectDelegate}.
	 * <p>
	 * Special behavior:
	 * <ul>
	 *   <li>If {@code whereObject} is used with a property path, it is converted
	 *       into a corresponding query clause.</li>
	 *   <li>Delegates to an overload of SelectDelegate depending on whether
	 *       {@code whereObject} is present.</li>
	 * </ul>
	 *
	 * @param selectClass             class of objects to select
	 * @param queryWhere              where clause
	 * @param params                  where-clause parameters
	 * @param queryOrder              order by clause
	 * @param whereObject             optional object used for reverse-path lookup
	 * @param propertyFromWhereObject property path used with whereObject
	 * @param extraWhere              extra where clause
	 * @param max                     maximum rows
	 * @param filter                  unused for JDBC
	 * @param bDirty                  include dirty objects
	 * @return an iterator for selected objects
	 */
	@Override
	public OADataSourceIterator select(Class selectClass,
			String queryWhere, Object[] params, String queryOrder,
			OAObject whereObject, String propertyFromWhereObject, String extraWhere,
			int max, OAFilter filter, boolean bDirty) {
		// 20200219 need to convert whereObject/properyFromWhereObject to part of query if it's using a propertyPath
		if (whereObject != null && propertyFromWhereObject != null && propertyFromWhereObject.indexOf(".") >= 0) {
			OAPath pp = new OAPath(whereObject.getClass(), propertyFromWhereObject, true);
			pp = pp.getReversePropertyPath();
			if (OAString.isNotEmpty(queryWhere)) {
				queryWhere += " AND ";
			} else if (queryWhere == null) {
				queryWhere = "";
			}
			queryWhere += pp.getPropertyPath() + " == ?";
			params = OAArray.add(Object.class, params, whereObject);
			whereObject = null;
			propertyFromWhereObject = null;
		}

		if (whereObject != null) {
			// 20220803
			return SelectDelegate.select(	this, selectClass,
											whereObject, propertyFromWhereObject,
											queryWhere, params,
											extraWhere, queryOrder, max, bDirty);
			/*was:
			return SelectDelegate.select(	this, selectClass,
											whereObject, extraWhere, params, propertyFromWhereObject,
											queryOrder, max, bDirty);
											*/

		}
		return SelectDelegate.select(	this, selectClass,
										queryWhere, params, queryOrder,
										max, bDirty);
	}

	/*
	 * Note: queryWhere needs to begin with "FROM TABLENAME WHERE ...", the queryOrder will be prefixed with "ORDER BY ". This is for cases
	 * where there are joins, etc.
	 */
	/**
	 * Performs a passthrough SELECT query. Assumes the {@code queryWhere} begins
	 * with a full SQL FROM/WHERE clause. Delegates to
	 * {@link SelectDelegate#selectPassthru}.
	 *
	 * @param selectClass the class to select
	 * @param queryWhere  SQL fragment beginning with FROM
	 * @param queryOrder  ORDER BY clause
	 * @param max         maximum results
	 * @param filter      unused
	 * @param bDirty      include dirty objects
	 * @return an iterator for selected results
	 */
	public OADataSourceIterator selectPassthru(Class selectClass,
			String queryWhere, String queryOrder,
			int max, OAFilter filter, boolean bDirty) {
		return SelectDelegate.selectPassthru(this, selectClass, queryWhere, queryOrder, max, bDirty);
	}

	/**
	 * Executes an SQL command by delegating to {@link SelectDelegate#execute}.
	 *
	 * @param command SQL command to execute
	 * @return result returned by the delegate
	 */
	public @Override Object execute(String command) {
		return SelectDelegate.execute(this, command);
	}

	/**
	 * Counts objects matching the specified query. If a {@code whereObject} is
	 * supplied, delegates to the SelectDelegate overload that accepts an object
	 * key. Otherwise, delegates to the basic count implementation.
	 *
	 * @param selectClass             class to count
	 * @param queryWhere              where clause
	 * @param params                  parameters
	 * @param whereObject             optional object used for reverse lookup
	 * @param propertyFromWhereObject property path from whereObject
	 * @param extraWhere              extra where clause
	 * @param max                     limit
	 * @return the count result
	 */
	@Override
	public int count(Class selectClass,
			String queryWhere, Object[] params,
			OAObject whereObject, String propertyFromWhereObject, String extraWhere, int max) {
		if (whereObject != null) {
			return SelectDelegate.count(this, selectClass, whereObject, propertyFromWhereObject, queryWhere, params, extraWhere, max);
		}
		return SelectDelegate.count(this, selectClass, queryWhere, params, max);
	}

	/**
	 * Performs a passthrough COUNT query for the specified SELECT fragment by
	 * delegating to {@link SelectDelegate#countPassthru}.
	 *
	 * @param selectClass class to count
	 * @param queryWhere  raw SQL WHERE fragment
	 * @param max         maximum results considered
	 * @return the counted value
	 */
	@Override
	public int countPassthru(Class selectClass, String queryWhere, int max) {
		return SelectDelegate.countPassthru(this, queryWhere, max);
	}

	/**
	 * Returns a JDBC {@link Statement} from the connection pool using a default
	 * diagnostic message. Must be released using {@link #releaseStatement}.
	 *
	 * @return a Statement from the pool
	 */
	public Statement getStatement() {
		return getStatement("OADataSourceJDBC.getStatement()");
	}

	/**
	 * Returns a JDBC {@link Statement} from the connection pool. Uses the
	 * supplied message for connection-pool diagnostics.
	 *
	 * @param message diagnostic message used when obtaining a statement
	 * @return a Statement from the pool
	 */
	public Statement getStatement(String message) {
		try {
			return connectionPool.getStatement(message);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns a batch-mode JDBC {@link Statement} from the connection pool if
	 * the current transaction supports batch operations. Returns null otherwise.
	 *
	 * @param message diagnostic message used to obtain the statement
	 * @return a batch statement or null if batching is unavailable
	 */
	public Statement getBatchStatement(String message) {
		try {
			return connectionPool.getBatchStatement(message);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Releases the specified {@link Statement} back to the connection pool.
	 *
	 * @param statement the Statement to release
	 */
	public void releaseStatement(Statement statement) {
		if (statement != null) {
			connectionPool.releaseStatement(statement);
		}
	}

	/**
	 * Returns a {@link PreparedStatement} for the given SQL text. Must be released
	 * using {@link #releasePreparedStatement(PreparedStatement)}.
	 *
	 * @param sql the SQL string used to create the PreparedStatement
	 * @return a prepared statement
	 */
	public PreparedStatement getPreparedStatement(String sql) {
		return getPreparedStatement(sql, false);
	}

	/**
	 * Returns a {@link PreparedStatement} for the given SQL string, optionally
	 * configured to return auto-generated keys.
	 *
	 * @param sql               SQL text
	 * @param bHasAutoGenerated true to request generated keys
	 * @return a prepared statement
	 */
	public PreparedStatement getPreparedStatement(String sql, boolean bHasAutoGenerated) {
		try {
			return connectionPool.getPreparedStatement(sql, bHasAutoGenerated);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns a batch-mode {@link PreparedStatement} if the current transaction
	 * supports batching. Returns null otherwise.
	 *
	 * @param sql SQL used to create the PreparedStatement
	 * @return a batch prepared statement or null
	 */
	public PreparedStatement getBatchPreparedStatement(String sql) {
		try {
			return connectionPool.getBatchPreparedStatement(sql);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Releases the specified {@link PreparedStatement} back to the connection
	 * pool. Marks it as non-reusable.
	 *
	 * @param ps the prepared statement
	 */
	public void releasePreparedStatement(PreparedStatement ps) {
		if (ps != null) {
			connectionPool.releasePreparedStatement(ps, false);
		}
	}

	/**
	 * Releases the specified {@link PreparedStatement} back to the connection
	 * pool, specifying whether it may be reused without recompilation.
	 *
	 * @param ps            the prepared statement
	 * @param bCanBeReused  true if the statement is reusable
	 */
	public void releasePreparedStatement(PreparedStatement ps, boolean bCanBeReused) {
		if (ps != null) {
			connectionPool.releasePreparedStatement(ps, bCanBeReused);
		}
	}

	/**
	 * Closes this datasource by removing it from the active datasource list,
	 * closing the connection pool, and closing associated metadata resources.
	 */
	public void close() {
		super.close(); // remove from list of available datasources
		if (connectionPool != null) {
			connectionPool.close();
		}
		DBMetaDataDelegate.close(dbmd);
	}

	/**
	 * Reopens this datasource after it has been closed or reset. Calls the
	 * superclass implementation and reopens the connection pool.
	 *
	 * @param pos the reopen position (used by superclass)
	 */
	@Override
	public void reopen(int pos) {
		super.reopen(pos);
		if (connectionPool != null) {
			connectionPool.open();
		}
	}

	/**
	 * Closes all JDBC connections in the connection pool. Intended for full
	 * shutdown or diagnostic cleanup.
	 */
	public void closeAllConnections() {
		if (connectionPool != null) {
			connectionPool.closeAllConnections();
		}
	}

	/**
	 * Obtains a JDBC connection from the connection pool. The connection is
	 * non-exclusive (shared) unless otherwise configured.
	 *
	 * @return a JDBC connection
	 * @throws Exception if the connection cannot be obtained
	 */
	public Connection getConnection() throws Exception {
		return connectionPool.getConnection(false);
	}

	/**
	 * Obtains a JDBC connection from the connection pool.
	 *
	 * @param bExclusive true to request an exclusive connection
	 * @return a JDBC connection, exclusive if requested
	 * @throws Exception if the connection cannot be obtained
	 */
	public Connection getConnection(boolean bExclusive) throws Exception {
		return connectionPool.getConnection(bExclusive);
	}

	/**
	 * Releases a JDBC connection back to the connection pool if it is non-null.
	 *
	 * @param connection the connection to release
	 */
	public void releaseConnection(Connection connection) {
		if (connection != null) {
			connectionPool.releaseConnection(connection);
		}
	}

	/**
	 * Performs datasource verification by invoking the {@link VerifyDelegate}.
	 * Prints a blank line before running verification.
	 *
	 * @return the result of the verification process
	 * @throws Exception if verification fails
	 */
	public boolean verify() throws Exception {
		System.out.println("");
		return VerifyDelegate.verify(this);
	}

	/**
	 * Retrieves a BLOB value for the specified object and property by delegating
	 * to {@link SelectDelegate#getPropertyBlobValue}. Logs a warning on error.
	 *
	 * @param obj          the object owning the property
	 * @param propertyName the property name
	 * @return the blob value, or null if unavailable or error occurs
	 */
	@Override
	public byte[] getPropertyBlobValue(OAObject obj, String propertyName) {
		byte[] result = null;
		try {
			result = SelectDelegate.getPropertyBlobValue(this, obj, propertyName);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "error getting blob value", e);
		}
		return result;
	}

	/**
	 * Retrieves a single object from the datasource using the specified class
	 * and key. Delegates to {@link SelectDelegate#selectObject}; if no iterator
	 * is returned, falls back to {@link OADataSource#getObject}.
	 *
	 * @param oi     the OAObjectInfo for the class
	 * @param clazz  the object's class
	 * @param key    the object's key
	 * @param bDirty include dirty objects
	 * @return the retrieved object, or null if not found
	 */
	@Override
	public Object getObject(OAObjectInfo oi, Class clazz, OAObjectKey key, boolean bDirty) {
		Object obj = null;
		try {
			Iterator it = SelectDelegate.selectObject(this, clazz, key, bDirty);
			if (it == null) {
				return super.getObject(oi, clazz, key, bDirty);
			}
			obj = it.next();
		} catch (Exception e) {
			LOG.log(Level.WARNING, "error getting object, class=" + clazz, e);
		}
		return obj;
	}

	/**
	 * Retrieves the maximum allowed length for the given property by delegating
	 * to {@link Delegate#getPropertyMaxLength}.
	 *
	 * @param c            the class defining the property
	 * @param propertyName the property name
	 * @return the maximum length value
	 */
	@Override
	public int getMaxLength(Class c, String propertyName) {
		int x = Delegate.getPropertyMaxLength(this, c, propertyName);
		return x;
	}

	/**
	 * Assigns a GUID to this datasource and updates the underlying metadata to
	 * reflect the same GUID. Delegates to {@link OADataSource#setGuid}.
	 *
	 * @param guid the new datasource GUID
	 */
	@Override
	public void setGuid(String guid) {
		super.setGuid(guid);
		getDBMetaData().guid = guid;
	}

	/**
	 * Retrieves many-to-many link entries for the specified link information
	 * by delegating to {@link SelectDelegate#getManyToMany}.
	 *
	 * @param linkInfo the link information object
	 * @return a list of many-to-many entries, or null if linkInfo is null
	 */
	public ArrayList<ManyToMany> getManyToMany(OALinkInfo linkInfo) {
		if (linkInfo == null) {
			return null;
		}
		ArrayList<ManyToMany> al = SelectDelegate.getManyToMany(this, linkInfo);
		return al;
	}

	/**
	 * Updates the database sequence values for auto-assigned primary keys
	 * belonging to the specified class. If supported, the method synchronizes
	 * the database sequence with the current maximum identifier value.
	 *
	 * @param clazz the OAObject class whose sequence should be updated
	 */
	public void updateAutoSequence(Class<? extends OAObject> clazz) {
		if (dbmd == null || clazz == null) {
			return;
		}

		// OAObjectInfo oi = OAObjectInfoDelegate.getOAObjectInfo(clazz);
		Table table = database.getTable(clazz);

		Column[] cols = table.getPrimaryKeyColumns();
		if (cols == null || cols.length == 0) {
			return;
		}
		if (cols[0].assignNextNumber) {
			Connection connection = null;
			Statement st = null;
			try {
				connection = getConnection();
				st = connection.createStatement();

				// make sure that db seq# is updated
				if (dbmd.getDatabaseType() == dbmd.POSTGRES) {
					String sql = "SELECT setval('" + table.name + "_id_seq', (SELECT MAX(id) FROM " + table.name + "))";
					LOG.fine(sql);
					st.execute(sql);
					st.close();
				}
			} catch (Exception e) {
				LOG.log(Level.WARNING, "exception while updating seq, will continue", e);
			} finally {
				if (st != null) {
					try {
						st.close();
					}
					catch (Exception ex) {};
				}
				releaseConnection(connection);
			}
		}
	}

}
