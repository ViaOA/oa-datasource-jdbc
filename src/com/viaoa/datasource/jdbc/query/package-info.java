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
 * Provides the query translation and result-streaming layer for the OA JDBC data source.
 * <p>
 * Classes in this package convert OA object-graph queries into SQL and iterate over JDBC
 * {@link java.sql.ResultSet}s to construct OAObjects. They form the bridge between the
 * object-model abstraction and relational database access.
 * <ul>
 *   <li>{@link com.viaoa.datasource.jdbc.query.QueryConverter} — Parses and builds SQL from OA queries.</li>
 *   <li>{@link com.viaoa.datasource.jdbc.query.ResultSetIterator} — Executes SQL and streams results into OAObjects.</li>
 *   <li>{@link com.viaoa.datasource.jdbc.query.FreeTextConverter} — Handles database-specific full-text syntax.</li>
 * </ul>
 * This layer is internal to OA-JDBC and typically used indirectly via
 * {@link com.viaoa.datasource.jdbc.db.DataAccessObject}.
 */
package com.viaoa.datasource.jdbc.query;
