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
package com.viaoa.datasource.jdbc.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.runtime.OARuntime;
import com.viaoa.transaction.OATransaction;
import com.viaoa.transaction.OATransactionListener;

/**
 * Manages a dynamic pool of {@link java.sql.Connection} instances for OA's JDBC layer.
 * <p>
 * Each {@link OAConnection} wraps a JDBC connection and handles pooled statements.
 * {@code ConnectionPool} maintains minimum and maximum pool sizes, and uses a
 * low-priority background thread to periodically prune idle or invalid connections.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Automatic connection validation and cleanup every 10 minutes.</li>
 *   <li>Integration with {@link com.viaoa.transaction.OATransaction} for commit/rollback.</li>
 *   <li>Supports batch operations and per-thread transaction isolation.</li>
 *   <li>Optional statement and prepared statement pooling via {@link OAConnection}.</li>
 * </ul>
 *
 * Thread-safe for concurrent acquisition and release of connections.
 * Typically used internally by {@link com.viaoa.datasource.jdbc.OADataSourceJDBC}.
 *
 * @since OA 4.0
 */
public class ConnectionPool implements Runnable {
	private static Logger LOG = Logger.getLogger(ConnectionPool.class.getName());

	/**
	 * Metadata describing the target JDBC database, including driver, URL,
	 * credentials, and pool configuration such as minimum and maximum
	 * connection counts.
	 */
	private DBMetaData dbmd;
	
	/**
	 * The list of pooled {@link OAConnection} instances managed by this pool.
	 * Connections in the list may be available, in-use, or pending cleanup.
	 */
	private ArrayList<OAConnection> alOAConnection = new ArrayList<OAConnection>();
	
	/**
	 * Background monitor thread that periodically validates connections and
	 * prunes unused ones. Runs every 10 minutes while the pool is open.
	 */
	private transient Thread thread; // used to release connections
	
	/**
	 * Flag used to signal the monitor thread to stop. Set when the pool is
	 * being closed.
	 */
	private boolean bStopThread; // tells thread to stop
	
	/**
	 * Synchronization lock used by the monitor thread for timed waiting
	 * and wake-up notifications during shutdown.
	 */
	private Object threadLOCK = new Object();

	/**
	 * Reentrant lock protecting access to the internal connection list and
	 * preventing concurrent modification of pooled-connection state.
	 */
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Constructs a new connection pool using the supplied database metadata.
	 * Initializes internal structures and starts the background monitor thread.
	 *
	 * @param dbmd metadata describing driver, URL, credentials, and pool limits
	 */
	public ConnectionPool(DBMetaData dbmd) {
		this.dbmd = dbmd;

		// start a monitor thread that will release connections when not used
		open();
	}

	/**
	 * Starts the background monitor thread if it is not already running.
	 * Resets the stop flag and initializes a daemon thread named
	 * “OAConnectionPool”.
	 */
	public void open() {
		bStopThread = false;
		if (thread == null) {
			thread = new Thread(this, "OAConnectionPool"); // used to release connections
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			thread.start();
		}
	}

	/**
	 * Stops the monitor thread, wakes it from waiting, and closes all connections
	 * in the pool. After calling this, the pool is no longer usable.
	 */
	public void close() {
		if (thread != null) {
			thread = null;
			bStopThread = true;
			synchronized (threadLOCK) {
				threadLOCK.notifyAll();
			}
			closeAllConnections();
		}
	}

	/**
	 * Background monitor thread that maintains pool health. Approximately every
	 * 10 minutes it:
	 * <ul>
	 *   <li>Validates each existing connection.</li>
	 *   <li>Closes invalid or idle connections beyond the minimum threshold.</li>
	 *   <li>Creates new connections when fewer than {@code dbmd.minConnections} exist.</li>
	 * </ul>
	 * The thread stops when {@link #bStopThread} becomes true.
	 */
	public void run() {
		if (dbmd.minConnections < 1) {
			LOG.warning("dbmd.minConnections=" + dbmd.minConnections + ", will use one instead");
			dbmd.minConnections = 1;
		}
		if (dbmd.maxConnections < dbmd.minConnections) {
			LOG.warning("invalid dbmd.maxConnections=" + dbmd.maxConnections + " is less then dbmd.minConnections=" + dbmd.minConnections
					+ ", will use " + dbmd.minConnections + "+1 for max");
			dbmd.maxConnections = dbmd.minConnections + 1;
		}
		for (; !bStopThread;) {
			int cntAvailable = 0;
			int cntClosed = 0;

			try {

				for (int i = 0; i < alOAConnection.size(); i++) {
					OAConnection con = alOAConnection.get(i);
					if (con.connection.isClosed()) {
						continue;
					}
					if (!con.connection.isValid(5)) {
						if (!con.connection.isClosed()) {
							con.connection.rollback();
						}
						con.connection.close();
					}
				}

				lock.lock();
				for (int i = 0; i < alOAConnection.size(); i++) {
					OAConnection con = alOAConnection.get(i);
					if (con.connection.isClosed()) {
						alOAConnection.remove(i);
						i--;
						continue;
					}
					if (!con.bAvailable) {
						continue;
					}
					if (con.getTotalUsed() > 0) {
						continue;
					}
					if (++cntAvailable <= dbmd.minConnections) {
						continue; // keep min connections
					}

					con.connection.close();
					alOAConnection.remove(i);
					i--;

					if (++cntClosed == 2) {
						break; // only release max 2 at each check.
					}
				}
			} catch (java.sql.SQLException e) {
				LOG.log(Level.WARNING, "exception while checking connections, will continue", e);
			} finally {
				lock.unlock();
			}

			for (int i = cntAvailable; i < dbmd.minConnections && (alOAConnection.size() < dbmd.maxConnections); i++) {
				boolean bLocked = false;
				try {
					OAConnection con = createNewOAConnection();
					bLocked = true;
					lock.lock();
					if (alOAConnection.size() >= dbmd.maxConnections) {
						break;
					}
					con.bAvailable = true;
					alOAConnection.add(con);
				} catch (Exception e) {
					LOG.log(Level.WARNING, "error trying to create a new JDBC connection", e);
				} finally {
					if (bLocked) {
						lock.unlock();
					}
				}
			}

			try {
				synchronized (threadLOCK) {
					if (!bStopThread) {
						int ms = 1000 * 60 * 10;
						threadLOCK.wait(ms);
					}
				}
			} catch (InterruptedException e) {
			}
		}
	}

	/**
	 * Checks whether the database is reachable by acquiring and immediately
	 * releasing a JDBC {@link Statement}. Logs and returns false if an error
	 * occurs.
	 *
	 * @return true if the database can be accessed
	 */
	public boolean isDatabaseAvailable() {
		try {
			Statement st = getStatement("OADataSourceJDBC.ConnectionPool.isDatabaseAvailable()");
			releaseStatement(st);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "error checking database", e);
			return false;
		}
		return true;
	}

	/**
	 * Closes all connections currently in the pool and clears the internal list.
	 * Any exceptions during close are logged but do not halt processing.
	 */
	public void closeAllConnections() {
		try {
			lock.lock();
			for (OAConnection con : alOAConnection) {
				try {
					if (!con.connection.isClosed()) {
						con.connection.close();
					}
				} catch (Exception e) {
					System.out.println("Connection.close() exception: " + e);
					e.printStackTrace();
				}
			}
			alOAConnection.clear();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Acquires a JDBC connection from the pool. If {@code bExclusive} is true,
	 * only connections with zero active statements are eligible. Returns null
	 * if the pool is at its maximum size and no connections are available.
	 *
	 * @param bExclusive true to request an exclusive (unused) connection
	 * @return a JDBC connection or null if none available
	 * @throws Exception if acquisition fails
	 */
	public Connection getConnection(boolean bExclusive) throws Exception {
		OAConnection c = getOAConnection(false, bExclusive);
		if (c == null) {
			return null;
		}
		return c.connection;
	}

	/**
	 * Counter used to offset selection of the next candidate connection,
	 * providing round-robin distribution across the connection list.
	 */
	private final AtomicInteger aiGetConnection = new AtomicInteger();

	/**
	 * Counter tracking how many connections are in the process of being created.
	 * Used to prevent exceeding the configured maximum connection count.
	 */
	private int cntCreateConnection;

	/**
	 * Core method for acquiring an {@link OAConnection}. Supports:
	 * <ul>
	 *   <li>Thread-local transactions via {@link OAThreadLocalDelegate}.</li>
	 *   <li>Statement pooling and exclusive/non-exclusive usage rules.</li>
	 *   <li>Creation of new connections when allowed and needed.</li>
	 * </ul>
	 * Returns null only when the pool is at maximum capacity and all
	 * connections are in use.
	 *
	 * @param bForStatement true if acquiring for a Statement or PreparedStatement
	 * @param bExclusive    true if connection must be unused
	 * @return an OAConnection or null when unavailable
	 * @throws Exception if connection acquisition fails
	 */
	protected OAConnection getOAConnection(boolean bForStatement, boolean bExclusive) throws Exception {
		final OATransaction tran = OARuntime.thread().getTransaction();

		OAConnection con = null;
		if (tran != null) {
			con = (OAConnection) tran.get(this);
			if (con != null) {
				return con;
			}
			bExclusive = true;
		}
		if (!bExclusive && !dbmd.getAllowStatementPooling()) {
			bExclusive = true;
		}

		try {
			lock.lock();

			final int max = alOAConnection.size();
			final int spos = aiGetConnection.getAndIncrement();

			for (int i = 0; i < max; i++) {
				OAConnection conx = alOAConnection.get((spos + i) % max);
				if (!conx.bAvailable) {
					continue;
				}
				int used = conx.getTotalUsed();
				if (bExclusive) {
					if (used > 0) {
						continue;
					}
				}
				if (conx.connection.isClosed()) {
					continue;
				}
				if (con == null || used <= con.getTotalUsed()) {
					con = conx;
					if (used == 0) {
						break;
					}
				}
			}

			boolean bMaxed = ((alOAConnection.size() + cntCreateConnection) >= dbmd.maxConnections);
			if (con != null) {
				int used = con.getTotalUsed();
				if (used > 0 && !bMaxed) {
					con = null;
				} else {
					con.bAvailable = !bExclusive;
					if (bForStatement) {
						con.bGettingStatement = true;
					}
				}
			} else if (bMaxed) {
				return null;
			}
		} finally {
			if (con == null) {
				cntCreateConnection++;
			}
			lock.unlock();
		}

		if (con == null) {
			con = createNewOAConnection();
			try {
				lock.lock();
				con.bAvailable = !bExclusive;
				if (bForStatement) {
					con.bGettingStatement = true;
				}
				alOAConnection.add(con);
			} finally {
				cntCreateConnection--;
				lock.unlock();
			}
		}

		if (tran != null) {
			con.connection.setTransactionIsolation(tran.getTransactionIsolationLevel());
			con.connection.setAutoCommit(false);
			tran.put(this, con);
			MyOATransactionListener tl = new MyOATransactionListener(con);
			tran.addTransactionListener(tl);
		}
		return con;
	}

	/**
	 * Creates a brand-new JDBC connection using driver, URL, user, and password
	 * from {@link DBMetaData}. Wraps the connection in a new {@link OAConnection}
	 * configured for auto-commit and READ_COMMITTED isolation.
	 *
	 * @return a new OAConnection instance
	 * @throws Exception if driver loading or connection creation fails
	 */
	protected OAConnection createNewOAConnection() throws Exception {
		Class.forName(dbmd.driverJDBC).newInstance();
		Connection connection = DriverManager.getConnection(dbmd.urlJDBC, dbmd.user, dbmd.password);
		connection.setAutoCommit(true);
		connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
		OAConnection oacon = new OAConnection(connection);
		return oacon;
	}

	/**
	 * Releases a JDBC connection back to the pool, resetting auto-commit and
	 * isolation level. Marks the underlying {@link OAConnection} as available.
	 *
	 * @param connection the connection to release
	 */
	public void releaseConnection(Connection connection) {
		try {
			lock.lock();
			for (OAConnection con : alOAConnection) {
				if (con.connection != connection) {
					continue;
				}
				try {
					connection.setAutoCommit(true);
					connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
					con.bAvailable = true;
				} catch (SQLException e) {
					LOG.log(Level.WARNING, "releaseConnection() exception", e);
				}
				break;
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Acquires an {@link OAConnection} specifically for creating JDBC statements.
	 * Repeatedly attempts to get a usable connection, sleeping briefly between
	 * attempts until one becomes available.
	 *
	 * @return an OAConnection suitable for statement creation
	 * @throws Exception if acquisition fails
	 */
	protected OAConnection getStatementConnection() throws Exception {
		for (int i = 0;; i++) {
			OAConnection c = getOAConnection(true, false);
			if (c != null) {
				return c;
			}
			Thread.sleep(25);
		}
		// return null;
	}

	/**
	 * Transaction listener bound to a specific {@link OAConnection}. Ensures that
	 * commit, rollback, and batch execution operations are forwarded to the
	 * connection when an {@link OATransaction} completes or is aborted.
	 */
	class MyOATransactionListener implements OATransactionListener {
		/**
		 * The OAConnection associated with the transaction listener. All commit and
		 * rollback operations apply directly to this connection.
		 */
		OAConnection conx;

		/**
		 * Constructs a transaction listener bound to a specific connection.
		 *
		 * @param con the OAConnection associated with this listener
		 * @throws Exception not thrown by this implementation
		 */
		public MyOATransactionListener(OAConnection con) throws Exception {
			this.conx = con;
		}

		/**
		 * Commits the underlying JDBC connection. If batch mode is enabled, executes
		 * all open batches before committing. The connection is released back to the
		 * pool after completion.
		 *
		 * @param tran the transaction being committed
		 */
		@Override
		public void commit(OATransaction tran) {
			if (conx == null) {
				return;
			}
			try {
				// final OATransaction tran = OARuntime.threadLocals().getTransaction();
				if (tran != null && tran.getUseBatch()) {
					conx.executeOpenBatches();
				}
				conx.connection.commit();
			} catch (SQLException e) {
				LOG.log(Level.WARNING, "OATransactionListener.commit()", e);
			} finally {
				releaseConnection(conx.connection);
			}
		}

		/**
		 * Rolls back the underlying JDBC connection. If batch mode is enabled, clears
		 * pending batches before rolling back. The connection is released afterward.
		 *
		 * @param tran the transaction being rolled back
		 */
		@Override
		public void rollback(OATransaction tran) {
			if (conx == null) {
				return;
			}

			try {
				// final OATransaction tran = OARuntime.threadLocals().getTransaction();
				if (tran != null && tran.getUseBatch()) {
					conx.clearOpenBatches();
				}
				conx.connection.rollback();
			} catch (SQLException e) {
				LOG.log(Level.WARNING, "OATransactionListener.rollback()", e);
			} finally {
				releaseConnection(conx.connection);
			}
		}

		/**
		 * Executes open batches on the associated connection when batch mode is in
		 * use. Called during certain transaction phases.
		 *
		 * @param tran the transaction invoking batch execution
		 */
		@Override
		public void executeOpenBatches(OATransaction tran) {
			if (conx == null) {
				return;
			}
			try {
				// final OATransaction tran = OARuntime.threadLocals().getTransaction();
				if (tran != null && tran.getUseBatch()) {
					conx.executeOpenBatches();
				}
			} catch (SQLException e) {
				LOG.log(Level.WARNING, "OATransactionListener.executeBatchWork()", e);
			}
		}
	}

	/**
	 * Obtains a JDBC {@link Statement} from the pool for direct SQL execution.
	 * Delegates to the internal @_getStatement method with batch mode disabled.
	 *
	 * @param message diagnostic label attached to the statement acquisition
	 * @return a JDBC Statement
	 * @throws Exception if statement acquisition fails
	 */
	public Statement getStatement(String message) throws Exception {
		Statement st = _getStatement(message, false);
		return st;
	}

	/**
	 * Obtains a JDBC {@link Statement} intended for batch execution when
	 * transaction batch mode is enabled. Returns null if batch mode is inactive.
	 *
	 * @param message diagnostic label for batch statement acquisition
	 * @return a batch-enabled Statement or null if unsupported
	 * @throws Exception if acquisition fails
	 */
	public Statement getBatchStatement(String message) throws Exception {
		Statement st = _getStatement(message, true);
		return st;
	}

	/**
	 * Internal helper for obtaining a JDBC Statement. Retrieves an
	 * {@link OAConnection} appropriate for statement creation, requests either a
	 * normal or batch statement, and configures timeout and row limits.
	 *
	 * @param message   diagnostic label for the request
	 * @param bForBatch true to request a batch statement
	 * @return a configured JDBC Statement
	 * @throws Exception if acquisition fails or connection is invalid
	 */
	private Statement _getStatement(String message, boolean bForBatch) throws Exception {
		OAConnection con = getStatementConnection();
		Statement statement;
		try {
			if (bForBatch) {
				statement = con.getBatchStatement(message);
			} else {
				statement = con.getStatement(message);
			}
		} catch (Exception e) {
			if (con != null && con.connection.isClosed()) {
				return getStatement(message);
			}
			throw e;
		}
		statement.setMaxRows(0);
		statement.setQueryTimeout(0);
		return statement;
	}

	/**
	 * Releases a JDBC {@link Statement} previously acquired through
	 * {@link #getStatement} or {@link #getBatchStatement}. Iterates over pooled
	 * connections to locate the owner and delegates release to it.
	 *
	 * @param statement the Statement to release
	 */
	public void releaseStatement(Statement statement) {
		if (statement == null) {
			return;
		}
		Object[] objs = null;
		try {
			lock.lock();
			objs = alOAConnection.toArray();
		} finally {
			lock.unlock();
		}
		for (Object objx : objs) {
			OAConnection con = (OAConnection) objx;
			if (con.releaseStatement(statement)) {
				break;
			}
		}
	}

	/**
	 * Obtains a JDBC {@link PreparedStatement} for a given SQL string. If
	 * auto-generated key support is enabled, the statement is configured to return
	 * generated keys. Delegates to internal prepared-statement acquisition logic.
	 *
	 * @param sql               SQL text for prepared statement creation
	 * @param bHasAutoGenerated true if auto-generated keys are expected
	 * @return a JDBC PreparedStatement
	 * @throws Exception if acquisition fails
	 */
	public PreparedStatement getPreparedStatement(String sql, boolean bHasAutoGenerated) throws Exception {
		PreparedStatement ps = _getPreparedStatement(sql, bHasAutoGenerated, false);
		return ps;
	}

	/**
	 * Obtains a batch-enabled {@link PreparedStatement} when transaction batch
	 * mode is active. Returns null if batch mode is not enabled.
	 *
	 * @param sql SQL text for prepared statement creation
	 * @return a batch-enabled PreparedStatement or null
	 * @throws Exception if acquisition fails
	 */
	public PreparedStatement getBatchPreparedStatement(String sql) throws Exception {
		PreparedStatement ps = _getPreparedStatement(sql, false, true);
		return ps;
	}

	/**
	 * Internal helper for preparing JDBC statements. Ensures pool configuration
	 * is valid, acquires a statement connection, and requests either a normal or
	 * batch prepared statement. Applies timeout and row-limit defaults.
	 *
	 * @param sql               SQL text
	 * @param bHasAutoGenerated true if auto-generated keys should be supported
	 * @param bForBatch         true for acquiring a batch prepared statement
	 * @return a configured PreparedStatement
	 * @throws Exception if acquisition fails or configuration is invalid
	 */
	private PreparedStatement _getPreparedStatement(final String sql, final boolean bHasAutoGenerated, final boolean bForBatch)
			throws Exception {
		if (dbmd.minConnections < 1) {
			throw new Exception(
					"OADataSourceJDBC.ConnectionPool.minimumConnections is less then one, call OADataSourceJDBC.setMinConnections(x) to set");
		}
		if (dbmd.maxConnections < dbmd.minConnections) {
			throw new Exception(
					"OADataSourceJDBC.ConnectionPool.maximumConnections is less then minimumConnections, call OADataSourceJDBC.setMaxConnections(x) to set");
		}

		OAConnection con = getStatementConnection();

		PreparedStatement ps;
		try {
			if (bForBatch) {
				ps = con.getBatchPreparedStatement(sql);
			} else {
				if (dbmd.getSupportsAutoAssign()) {
					ps = con.getPreparedStatement(sql, bHasAutoGenerated);
				} else {
					ps = con.getPreparedStatement(sql, false);
				}
			}
		} catch (Exception e) {
			if (con.connection.isClosed()) {
				return getPreparedStatement(sql, bHasAutoGenerated);
			}
			throw e;
		}
		ps.setQueryTimeout(0);
		ps.setMaxRows(0);
		return ps;
	}

	/**
	 * Releases a prepared statement back to the pool. Iterates through pooled
	 * connections to locate the owning connection and delegates release handling.
	 *
	 * @param statement     the PreparedStatement to release
	 * @param bCanBeReused  true if the statement may be cached for reuse
	 */
	public void releasePreparedStatement(PreparedStatement statement, boolean bCanBeReused) {
		if (statement == null) {
			return;
		}
		Object[] objs = null;
		try {
			lock.lock();
			objs = alOAConnection.toArray();
		} finally {
			lock.unlock();
		}
		for (Object objx : objs) {
			OAConnection con = (OAConnection) objx;
			if (con.releasePreparedStatement(statement, bCanBeReused)) {
				break;
			}
		}
	}

	/**
	 * Appends detailed connection-pool diagnostics to the supplied vector.
	 * Includes driver, URL, user, connection counts, and per-connection statistics
	 * such as statement usage, prepared-statement usage, and query counts.
	 *
	 * @param vec the vector to populate with diagnostic information
	 */
	public void getInfo(Vector<Object> vec) {
		vec.addElement("Driver: " + dbmd.driverJDBC);
		vec.addElement("URL: " + dbmd.urlJDBC);
		vec.addElement("User: " + dbmd.user);
		vec.addElement("Min Connections: " + dbmd.minConnections);
		vec.addElement("Max Connections: " + dbmd.maxConnections);
		vec.addElement("Connections");

		try {
			lock.lock();
			int cnter = 0;
			for (OAConnection con : alOAConnection) {
				String s = String.format(	"%d) JDBC Connection, Statements current=%d/used=%d/created=%,d/queries=%,d," +
						" Prepared current=%d/used=%d/created=%,d/queries=%,d",
											cnter++,
											con.alStatement.size(), con.getCurrentlyUsedStatementCount(), con.cntCreateStatement,
											con.cntGetStatement,
											con.getTotalPreparedStatements(), con.alUsedPreparedStatement.size(),
											con.cntCreatePreparedStatement, con.cntGetPreparedStatement);
				if (!con.bAvailable) {
					s += (" * connection not available");
				}

				vec.addElement(s);
				con.getInfo(vec);
			}
		} finally {
			lock.unlock();
		}
	}
}
