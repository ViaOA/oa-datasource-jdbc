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
 * Provides low-level JDBC connection pooling and statement management for OA's JDBC data source.
 * <p>
 * Classes in this package maintain and optimize database connections, manage
 * statement reuse, and coordinate transactional batching between the JDBC driver
 * and OA's transaction framework.
 * <ul>
 *   <li>{@link com.viaoa.datasource.jdbc.connection.ConnectionPool} — dynamic connection pool manager.</li>
 *   <li>{@link com.viaoa.datasource.jdbc.connection.OAConnection} — pooled connection wrapper with statement reuse.</li>
 * </ul>
 *
 * These classes are internal components of {@link com.viaoa.datasource.jdbc.OADataSourceJDBC}.
 */
package com.viaoa.datasource.jdbc.connection;
