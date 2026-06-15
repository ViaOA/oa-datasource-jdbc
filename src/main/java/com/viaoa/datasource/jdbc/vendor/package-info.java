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
 * ds.backup("DBBackup_2025_11_12");
 * ds.checkForCorruption();
 * ds.compress();
 * }</pre>
 *
 * @see com.viaoa.datasource.jdbc.OADataSourceJDBC
 * @see com.viaoa.datasource.jdbc.db.DBMetaData
 * @since OA 4.0
 */
package com.viaoa.datasource.jdbc.vendor;
