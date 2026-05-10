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
package com.viaoa.datasource.jdbc.vendor;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datasource.jdbc.db.Database;
import com.viaoa.lang.OAString;
import com.viaoa.log.OALogger;

/**
 * Derby-specific extension of {@link com.viaoa.datasource.jdbc.OADataSourceJDBC}
 * providing database maintenance utilities such as verification, backup,
 * rollforward recovery, and compression.
 * <p>
 * These methods rely on Derby's built-in system procedures
 * under {@code SYSCS_UTIL}, and are no-ops when connected to
 * non-Derby databases.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li><b>Integrity verification:</b> Invokes
 *       {@code SYSCS_UTIL.SYSCS_CHECK_TABLE('APP', table)} on each table
 *       to validate internal consistency.</li>
 *   <li><b>Online backup:</b> Uses
 *       {@code SYSCS_UTIL.SYSCS_BACKUP_DATABASE_AND_ENABLE_LOG_ARCHIVE_MODE}
 *       for full database snapshots with roll-forward logs.</li>
 *   <li><b>Roll-forward restore:</b> Opens the database with
 *       {@code rollForwardRecoveryFrom=<backupDir>} to apply archived logs.</li>
 *   <li><b>Table compression:</b> Executes
 *       {@code SYSCS_UTIL.SYSCS_COMPRESS_TABLE('APP', table, 1)} on all tables
 *       to reclaim unused pages.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * Typically invoked through administrative utilities or maintenance scripts.
 * All operations are logged via {@link com.viaoa.log.OALogger} at FINE level.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * OADerbyDataSource ds = new OADerbyDataSource(database, dbmd);
 * ds.backup("DBBackup");
 * ds.checkForCorruption();
 * ds.compress();
 * }</pre>
 *
 */
public class OADerbyDataSource extends OADataSourceJDBC {
	private static Logger LOG = OALogger.getLogger(OADerbyDataSource.class);

	/**
	 * Creates a new Derby-specific data source using the provided database
	 * configuration and metadata.
	 *
	 * @param database the database configuration used for connections
	 * @param dbmd the metadata describing the JDBC database type and settings
	 */
	public OADerbyDataSource(Database database, DBMetaData dbmd) {
		super(database, dbmd);
	}

	/**
	 * Performs Derby integrity verification across all tables in the {@code APP}
	 * schema. For each table, invokes
	 * {@code SYSCS_UTIL.SYSCS_CHECK_TABLE('APP', tableName)} and logs the
	 * resulting status. This method returns immediately when the configured
	 * {@link DBMetaData} is not Derby.
	 *
	 * @throws Exception if an error occurs while querying or verifying a table
	 */
	public void checkForCorruption() throws Exception {
		DBMetaData dbmd = getDBMetaData();
		if (dbmd == null || dbmd.getDatabaseType() != DBMetaData.DERBY) {
			return;
		}
		LOG.fine("Starting Database verification");

		String sql;
		Statement statement = null;
		try {
			statement = getStatement("verify database");

			sql = "SELECT t.tablename from sys.sysschemas s, sys.systables t " +
					"where CAST(s.schemaname AS VARCHAR(128)) = 'APP' AND s.schemaid = t.schemaid " +
					"ORDER BY t.tablename";

			ResultSet rs = statement.executeQuery(sql);
			ArrayList<String> alTable = new ArrayList<String>();
			for (int i = 0; rs.next(); i++) {
				alTable.add(rs.getString(1));
			}
			rs.close();

			int i = 0;
			for (String tableName : alTable) {
				LOG.fine("Verifiying database table " + tableName);
				LOG.fine((++i) + ") verify " + tableName);
				try {
					sql = "SELECT t.tablename, SYSCS_UTIL.SYSCS_CHECK_TABLE('APP', t.tablename) " +
							"from sys.systables t " +
							"where CAST(t.tablename AS VARCHAR(128)) = '" + tableName + "'";
					rs = statement.executeQuery(sql);
					for (; rs.next();) {
						LOG.fine(i + ") " + rs.getString(1) + " = " + rs.getShort(2));
					}
				} catch (Exception e) {
					LOG.log(Level.WARNING, "database verification for table " + tableName + "failed", e);
					throw e;
				}
			}

			LOG.fine("Completed Database verification");
		} finally {
			releaseStatement(statement);
		}
	}

	/**
	 * Executes a live backup of the Derby database, enabling log-archive mode so
	 * the backup can later be used for roll-forward recovery. The operation calls
	 * {@code SYSCS_UTIL.SYSCS_BACKUP_DATABASE_AND_ENABLE_LOG_ARCHIVE_MODE}.
	 * This method is a no-op when the configured {@link DBMetaData} is not Derby.
	 *
	 * @param backupDirectory target directory where the Derby backup files are
	 *                        written
	 * @throws Exception if Derby fails to execute the backup operation
	 */
	public void backup(String backupDirectory) throws Exception {
		DBMetaData dbmd = getDBMetaData();
		if (dbmd == null || dbmd.getDatabaseType() != DBMetaData.DERBY) {
			return;
		}

		LOG.fine("Starting Database backup to " + backupDirectory);

		Statement statement = null;
		try {
			statement = getStatement("backup database");
			// statement.execute("call SYSCS_UTIL.SYSCS_CHECKPOINT_DATABASE()");
			// statement.execute("call SYSCS_UTIL.SYSCS_BACKUP_DATABASE('"+backupDirectory+"')");

			// create a backup, that will store rollforward log files in the current db log directory.  The '1' will delete previous log files
			String sql = "call SYSCS_UTIL.SYSCS_BACKUP_DATABASE_AND_ENABLE_LOG_ARCHIVE_MODE('" + backupDirectory + "', 1)";
			statement.execute(sql);

			// this is the commad to disable log archive.  The '1' will delete previous log files
			// SYSCS_UTIL.SYSCS_DISABLE_LOG_ARCHIVE_MODE(1)

			// use this to restore
			// connect 'jdbc:derby:wombat;rollForwardRecoveryFrom=d:/backup/wombat';

			LOG.fine("Completed Database backup to " + backupDirectory);
		} finally {
			releaseStatement(statement);
		}
	}

	/**
	 * Restores a Derby database by opening it with a JDBC URL that includes
	 * {@code rollForwardRecoveryFrom=<backupDirectory>}, enabling Derby to apply
	 * archived log files and roll the database forward. After the restore, the
	 * data source is reopened. This method is a no-op when the configured
	 * {@link DBMetaData} is not Derby.
	 *
	 * @param backupDirectory directory containing a previously created Derby
	 *                        backup with log-archive mode enabled
	 * @throws Exception if the roll-forward recovery or connection attempt fails
	 */
	public void restore(String backupDirectory) throws Exception {
		DBMetaData dbmd = getDBMetaData();
		if (dbmd == null || dbmd.getDatabaseType() != DBMetaData.DERBY) {
			return;
		}
		LOG.fine("Starting forwardRestoreBackupDatabase from " + backupDirectory);
		close();

		Class.forName(dbmd.getDriverJDBC()).newInstance();

		if (backupDirectory != null) {
			backupDirectory = backupDirectory.replace('\\', '/');
		}
		String jdbcUrl = dbmd.getUrlJDBC() + ";rollForwardRecoveryFrom=" + backupDirectory;

		String s = dbmd.getUrlJDBC();
		s = OAString.field(s, ":", OAString.dcount(s, ":"));
		jdbcUrl += "/" + s;

		/// this will open the database and perform a rollForward
		Connection connection = DriverManager.getConnection(jdbcUrl, dbmd.user, dbmd.password);
		connection.close();

		LOG.fine("Completed Database forward restore from " + backupDirectory);
		reopen(0);
	}

	/**
	 * Reclaims unused space in all Derby tables within the {@code APP} schema by
	 * invoking {@code SYSCS_UTIL.SYSCS_COMPRESS_TABLE('APP', tableName, 1)} for
	 * each table. Table names are queried from Derby system catalogs. This method
	 * returns immediately when the configured {@link DBMetaData} is not Derby.
	 *
	 * @throws Exception if compression fails for any individual table
	 */
	public void compress() throws Exception {
		DBMetaData dbmd = getDBMetaData();
		if (dbmd == null || dbmd.getDatabaseType() != DBMetaData.DERBY) {
			return;
		}
		LOG.config("Starting Database compression");

		String sql;
		Statement statement = null;
		Connection connection = null;
		try {
			statement = getStatement("compress database");

			sql = "SELECT t.tablename from sys.sysschemas s, sys.systables t " +
					"where CAST(s.schemaname AS VARCHAR(128)) = 'APP' AND s.schemaid = t.schemaid " +
					"ORDER BY t.tablename";

			ResultSet rs = statement.executeQuery(sql);
			ArrayList<String> alTable = new ArrayList<String>();
			for (int i = 0; rs.next(); i++) {
				alTable.add(rs.getString(1));
			}
			rs.close();
			releaseStatement(statement);

			connection = getConnection();
			int i = 0;
			for (String tableName : alTable) {
				LOG.fine((++i) + ") compressing table " + tableName);
				try {
					sql = "call SYSCS_UTIL.SYSCS_COMPRESS_TABLE('APP', '" + tableName + "', 1)";
					CallableStatement cs = connection.prepareCall(sql);
					cs.execute();
					cs.close();
				} catch (Exception e) {
					LOG.log(Level.WARNING, "database compression for table " + tableName + "failed", e);
					throw e;
				}
			}

			LOG.fine("Completed Database verification");
		} finally {
			releaseConnection(connection);
		}

	}

}
