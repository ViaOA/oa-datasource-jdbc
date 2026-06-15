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
 * Internal delegate layer for the OA JDBC subsystem.
 * <p>
 * Classes in this package encapsulate focused responsibilities used by
 * {@link com.viaoa.datasource.jdbc.OADataSourceJDBC}: SQL generation,
 * vendor metadata wiring, autonumber assignment, DDL utilities, logging
 * and recovery parsing, and query execution helpers.
 * The design keeps the main DataSource small while enabling shared,
 * package-scoped access to JDBC/db internals.
 * </p>
 *
 * <h2>Key components</h2>
 * <ul>
 *   <li>Autonumber assignment and verification</li>
 *   <li>Value conversion and SQL literal handling</li>
 *   <li>Vendor metadata synchronization</li>
 *   <li>DDL generation helpers</li>
 *   <li>INSERT/SELECT/DELETE execution utilities</li>
 *   <li>Structured DB logging and recovery parsing</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * Not intended for direct application use; leveraged internally by the JDBC DataSource.
 *
 */
package com.viaoa.datasource.jdbc.delegate;
