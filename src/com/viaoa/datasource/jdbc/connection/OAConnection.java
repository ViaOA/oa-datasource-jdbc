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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.viaoa.runtime.OARuntime;
import com.viaoa.transaction.OATransaction;

/**
 * Wraps a JDBC {@link java.sql.Connection} to provide internal pooling for
 * {@link java.sql.Statement} and {@link java.sql.PreparedStatement} objects.
 * <p>
 * Each {@code OAConnection} is managed by a {@link ConnectionPool} and used
 * to efficiently create, reuse, and batch SQL statements across the OA JDBC layer.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Statement and PreparedStatement pooling with per-SQL reuse.</li>
 *   <li>Batch update support integrated with {@link com.viaoa.transaction.OATransaction}.</li>
 *   <li>Automatic execution or clearing of pending batches on transaction commit/rollback.</li>
 *   <li>Thread-safe tracking and diagnostics via internal counters.</li>
 * </ul>
 *
 * Instances are created and released exclusively by {@link ConnectionPool}.
 * Not intended for direct application use.
 *
 * @since OA 4.0
 */
public class OAConnection {
	private static Logger LOG = Logger.getLogger(OAConnection.class.getName());

	/**
	 * The underlying JDBC connection being wrapped and managed.
	 */
	protected final Connection connection;
	
	/**
	 * Pool of {@link Statement} objects managed by this connection.
	 */
	protected final List<Pool> alStatement = new ArrayList<>();
	
	/**
	 * List of {@link PreparedStatement} instances currently in use.
	 */
	protected final List<PreparedStatement> alUsedPreparedStatement = new ArrayList<>();
	
	/**
	 * List of {@link PreparedStatement} instances currently used for batch updates.
	 */
	protected final List<PreparedStatement> alUsedBatchPreparedStatement = new ArrayList<PreparedStatement>();
	
	/**
	 * Flag indicating whether this connection is currently available for use.
	 */
	protected volatile boolean bAvailable;
	
	/**
	 * Flag indicating that a statement is currently being acquired.
	 */
	protected volatile boolean bGettingStatement;

	/**
	 * Cache mapping SQL strings to pooled {@link PreparedStatement} instances.
	 */
	private final Map<String, List<PreparedStatement>> hmSqlToPreparedStatements = new ConcurrentHashMap<>();
	
	/**
	 * Reverse lookup map from {@link PreparedStatement} to its SQL string.
	 */
	private final Map<PreparedStatement, String> hmPreparedStatementToSql = new ConcurrentHashMap<PreparedStatement, String>();

	/**
	 * Counter tracking total calls to get a {@link Statement}.
	 */
	volatile int cntGetStatement;
	
	/**
	 * Counter tracking total created {@link Statement} instances.
	 */
	volatile int cntCreateStatement;
	
	/**
	 * Counter tracking total released {@link Statement} instances.
	 */
	volatile int cntReleaseStatement;

	/**
	 * Counter tracking total calls to get a {@link PreparedStatement}.
	 */
	volatile int cntGetPreparedStatement;
	
	/**
	 * Counter tracking total created {@link PreparedStatement} instances.
	 */
	volatile int cntCreatePreparedStatement;
	
	/**
	 * Counter tracking total released {@link PreparedStatement} instances.
	 */
	volatile int cntReleasePreparedStatement;

	/**
	 * Creates a new OAConnection wrapping the supplied JDBC connection.
	 *
	 * @param con the JDBC connection to wrap
	 */
	public OAConnection(Connection con) {
		connection = con;
	}

	/**
	 * Returns whether the current {@link OATransaction} allows batch operations.
	 *
	 * @return {@code true} if batch operations are allowed
	 */
	public boolean isAllowingBatch() {
		final OATransaction tran = OARuntime.thread().getTransaction();
		final boolean bIsForBatch = tran != null && tran.getUseBatch();
		return bIsForBatch;
	}

	/**
	 * Returns the underlying JDBC {@link Connection}.
	 *
	 * @return the wrapped connection
	 */
	public Connection getConnection() {
		return connection;
	}

	/**
	 * Returns a {@link Statement} configured for batch updates.
	 * <p>
	 * This method must be called within an {@link OATransaction} that allows batching.
	 *
	 * @param message diagnostic message associated with the statement
	 * @return a batch-enabled {@link Statement}, or {@code null} if batching is not allowed
	 * @throws SQLException if a JDBC error occurs
	 */
	public Statement getBatchStatement(String message) throws SQLException {
		Statement st = _getStatement(message, true);
		return st;
	}

	/**
	 * Returns a {@link Statement} for non-batch SQL execution.
	 *
	 * @param message diagnostic message associated with the statement
	 * @return a {@link Statement} instance
	 * @throws SQLException if a JDBC error occurs
	 */
	public Statement getStatement(String message) throws SQLException {
		Statement st = _getStatement(message, false);
		return st;
	}

	/**
	 * Internal method used to acquire a {@link Statement}, optionally configured
	 * for batch updates.
	 *
	 * @param message diagnostic message associated with the statement
	 * @param bBatchUpdate {@code true} to request a batch-enabled statement
	 * @return a {@link Statement} instance, or {@code null} if batch usage is not allowed
	 * @throws SQLException if a JDBC error occurs
	 */
	private Statement _getStatement(final String message, final boolean bBatchUpdate) throws SQLException {
		Statement statement = null;
		cntGetStatement++;

		final boolean bIsAllowingBatch = isAllowingBatch();
		if (bBatchUpdate && !bIsAllowingBatch) {
			return null;
		}

		if (!bBatchUpdate && bIsAllowingBatch) {
			executeOpenBatches();
		}

		synchronized (alStatement) {
			int x = alStatement.size();
			for (int i = 0; i < x; i++) {
				Pool pool = (Pool) alStatement.get(i);
				if (pool.used) {
					if (!bBatchUpdate) {
						continue;
					}
				}
				if (pool.statement.isClosed()) {
					alStatement.remove(i);
					i--;
					x--;
					continue;
				}
				pool.used = true;
				pool.bIsForBatch = bBatchUpdate;
				bGettingStatement = false;
				pool.message = message;
				return pool.statement;
			}
		}

		statement = connection.createStatement();
		cntCreateStatement++;

		Pool pool = new Pool(statement, true, message);
		pool.bIsForBatch = bBatchUpdate;
		synchronized (alStatement) {
			alStatement.add(pool);
			bGettingStatement = false;
		}

		if (alStatement.size() > 20) {
			LOG.warning("StatementPool is getting large, current=" + alStatement.size());
		}

		return statement;
	}

	/**
	 * Releases a {@link Statement} back to the internal pool.
	 * <p>
	 * If the statement was used for batching, any pending batch is cleared.
	 *
	 * @param statement the statement to release
	 * @return {@code true} if the statement was found and released
	 */
	public boolean releaseStatement(Statement statement) {

		boolean bResult = false;
		synchronized (alStatement) {
			int x = alStatement.size();
			for (int i = 0; i < x; i++) {
				Pool pool = (Pool) alStatement.get(i);
				if (pool.statement != statement) {
					continue;
				}

				try {
					if (pool.bIsForBatch) {
						pool.statement.clearBatch();
						pool.bIsForBatch = false;
					}

					pool.used = false;
					bResult = true;
					if (alStatement.size() < 10) {
						break;
					}
					if (!statement.isClosed()) {
						statement.close();
					}
				} catch (Exception e) {
					LOG.log(Level.WARNING, "Exception releasing statement", e);
				}
				alStatement.remove(i);
				break;
			}
		}
		if (bResult) {
			cntReleaseStatement++;
		}
		return bResult;
	}

	/**
	 * Executes all open batch statements and prepared statement batches.
	 *
	 * @throws SQLException if a JDBC error occurs during batch execution
	 */
	protected void executeOpenBatches() throws SQLException {
		for (Pool pool : alStatement) {
			if (pool.used && pool.bIsForBatch) {
				pool.statement.executeBatch();
				releaseStatement(pool.statement);
			}
		}
		for (PreparedStatement ps : hmPreparedStatementToSql.keySet()) {
			if (alUsedBatchPreparedStatement.contains(ps)) {
				ps.executeBatch();
				releasePreparedStatement(ps, true);
			}
		}
	}

	/**
	 * Clears and releases all open batch statements without executing them.
	 *
	 * @throws SQLException if a JDBC error occurs
	 */
	protected void clearOpenBatches() throws SQLException {
		for (Pool pool : alStatement) {
			if (pool.used && pool.bIsForBatch) {
				releaseStatement(pool.statement);
			}
		}
		for (PreparedStatement ps : hmPreparedStatementToSql.keySet()) {
			if (alUsedBatchPreparedStatement.contains(ps)) {
				releasePreparedStatement(ps, true);
			}
		}
	}

	/**
	 * Returns a {@link PreparedStatement} configured for batch updates.
	 * <p>
	 * This method must be called within an {@link OATransaction} that allows batching.
	 *
	 * @param sql the SQL statement
	 * @return a batch-enabled {@link PreparedStatement}, or {@code null} if batching is not allowed
	 * @throws SQLException if a JDBC error occurs
	 */
	public PreparedStatement getBatchPreparedStatement(String sql) throws SQLException {
		return _getPreparedStatement(sql, false, true);
	}

	/**
	 * Returns a {@link PreparedStatement} for the specified SQL.
	 *
	 * @param sql the SQL statement
	 * @param bHasAutoGenerated {@code true} if auto-generated keys are required
	 * @return a {@link PreparedStatement} instance
	 * @throws SQLException if a JDBC error occurs
	 */
	public PreparedStatement getPreparedStatement(String sql, boolean bHasAutoGenerated) throws SQLException {
		return _getPreparedStatement(sql, bHasAutoGenerated, false);
	}

	/**
	 * Internal method used to acquire a {@link PreparedStatement}, optionally
	 * configured for batch updates.
	 *
	 * @param sql the SQL statement
	 * @param bHasAutoGenerated {@code true} if auto-generated keys are required
	 * @param bBatchUpdate {@code true} to request batch usage
	 * @return a {@link PreparedStatement} instance, or {@code null} if batch usage is not allowed
	 * @throws SQLException if a JDBC error occurs
	 */
	private PreparedStatement _getPreparedStatement(final String sql, final boolean bHasAutoGenerated, final boolean bBatchUpdate)
			throws SQLException {

		final boolean bIsAllowingBatch = isAllowingBatch();
		if (bBatchUpdate && (!bIsAllowingBatch || bHasAutoGenerated)) {
			return null;
		}

		if (bIsAllowingBatch && !bBatchUpdate) {
			executeOpenBatches();
		}

		List<PreparedStatement> alPreparedStatement;
		synchronized (alUsedPreparedStatement) {
			cntGetPreparedStatement++;
			alPreparedStatement = hmSqlToPreparedStatements.computeIfAbsent(sql, k -> 
				{
					ArrayList<PreparedStatement> al = new ArrayList<PreparedStatement>();
					return al;
				}
			);
			
			
			for (PreparedStatement ps : alPreparedStatement) {
				if (!alUsedPreparedStatement.contains(ps)) {
					alUsedPreparedStatement.add(ps);
					bGettingStatement = false;
					if (!bBatchUpdate) {
						return ps;
					}
				}
				if (bBatchUpdate) {
					bGettingStatement = false;
					if (!alUsedBatchPreparedStatement.contains(ps)) {
						alUsedBatchPreparedStatement.add(ps);
					}
					return ps;
				}
			}
		}

		PreparedStatement ps;
		if (bHasAutoGenerated) {
			ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
		} else {
			ps = connection.prepareStatement(sql);
		}
		cntCreatePreparedStatement++;

		synchronized (alUsedPreparedStatement) {
			alUsedPreparedStatement.add(ps);
			if (bBatchUpdate) {
				alUsedBatchPreparedStatement.add(ps);
			}
			bGettingStatement = false;
			alPreparedStatement.add(ps);
			hmPreparedStatementToSql.put(ps, sql);
		}
		return ps;
	}

	/**
	 * Releases a {@link PreparedStatement} back to the internal pool.
	 * <p>
	 * Depending on usage and pool size, the statement may be reused or closed.
	 *
	 * @param ps the prepared statement to release
	 * @param bCanBeReused {@code true} if the statement may be reused
	 * @return {@code true} if the statement was found and released
	 */
	public boolean releasePreparedStatement(PreparedStatement ps, boolean bCanBeReused) {
		boolean bFound = false;
		synchronized (alUsedPreparedStatement) {
			int x = alUsedPreparedStatement.size();
			for (int i = 0; i < x; i++) {
				PreparedStatement ps2 = alUsedPreparedStatement.get(i);
				if (ps2 != ps) {
					continue;
				}

				if (alUsedBatchPreparedStatement.contains(ps)) {
					try {
						ps.clearBatch();
					} catch (Exception e) {
					}
					alUsedBatchPreparedStatement.remove(ps);
				}

				alUsedPreparedStatement.remove(i);
				bFound = true;
				break;
			}
		}

		synchronized (alUsedPreparedStatement) {
			// see if the ps can be closed and removed from cache.
			String sql = hmPreparedStatementToSql.get(ps);
			if (sql != null) {
				List<PreparedStatement> al = hmSqlToPreparedStatements.get(sql);

				if (al != null && ((bFound && !bCanBeReused) || al.size() > 5 || hmSqlToPreparedStatements.size() > 25)) {
					try {
						if (!ps.isClosed()) {
							ps.close();
						}
					} catch (Exception e) {
					}
					al.remove(ps);
					if (al.size() == 0) {
						hmSqlToPreparedStatements.remove(sql);
					}
					hmPreparedStatementToSql.remove(ps);
				}
			}
		}
		return bFound;
	}

	/**
	 * Returns the total number of statements and prepared statements currently in use.
	 *
	 * @return the total count of in-use statements
	 */
	public int getTotalUsed() {
		int x = alUsedPreparedStatement.size();
		x += getCurrentlyUsedStatementCount();
		if (bGettingStatement) {
			x++;
		}
		return x;
	}

	/**
	 * Returns the number of {@link Statement} instances currently in use.
	 *
	 * @return the count of active statements
	 */
	protected int getCurrentlyUsedStatementCount() {
		int totalUsed = 0;
		;
		synchronized (alStatement) {
			int x = alStatement.size();
			for (int i = 0; i < x; i++) {
				Pool pool = alStatement.get(i);
				if (pool.used) {
					totalUsed++;
				}
			}
		}
		return totalUsed;
	}

	/**
	 * Appends diagnostic information about this connection to the supplied list.
	 *
	 * @param vec the list to receive diagnostic messages
	 */
	public void getInfo(List vec) {
		try {
			if (connection.isClosed()) {
				vec.add("   Connection is closed");
			}
		} catch (Exception e) {
		}
		synchronized (alStatement) {
			int x = alStatement.size();
			for (int i = 0; i < x; i++) {
				Pool pool = alStatement.get(i);
				if (pool.used) {
					// vec.addElement("  "+i+") "+pool.message);
				}
			}
		}
		/*
		vec.add("   GetStatement count="+cntGetStatement);
		vec.add("   CreateStatement count="+cntCreateStatement);
		vec.add("   ReleaseStatement count="+cntReleaseStatement);
		vec.add("   GetPreparedStatement count="+cntGetPreparedStatement);
		vec.add("   CreatePreparedStatement count="+cntCreatePreparedStatement);
		vec.add("   ReleasePreparedStatement count="+cntReleasePreparedStatement);
		*/
	}

	/**
	 * Internal pool entry used to track a {@link Statement} and its usage state.
	 */
	class Pool {
		Statement statement;
		boolean used;
		String message;
		boolean bIsForBatch;

		public Pool(Statement s, boolean b, String message) {
			statement = s;
			used = b;
			this.message = message;
		}
	}

	/**
	 * Returns the total number of cached {@link PreparedStatement} instances.
	 *
	 * @return the total prepared statement count
	 */
	public int getTotalPreparedStatements() {
		int i = 0;
		for (Entry<String, List<PreparedStatement>> entry : hmSqlToPreparedStatements.entrySet()) {
			i += entry.getValue().size();
		}
		return i;
	}
}
