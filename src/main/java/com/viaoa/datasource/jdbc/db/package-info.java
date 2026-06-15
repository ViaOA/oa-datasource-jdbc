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
 * Provides the database schema-reflection model for the OA JDBC subsystem.
 * <p>
 * Classes in this package describe the logical and physical structure of
 * relational databases, including tables, columns, indexes, relationships,
 * and vendor-specific metadata.  They are used internally by
 * {@link com.viaoa.datasource.jdbc.OADataSourceJDBC} to translate
 * OAObject graphs into SQL statements.
 * </p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Represent database tables, columns, and indexes as Java objects.</li>
 *   <li>Model one-to-many, many-to-one, and many-to-many relationships.</li>
 *   <li>Expose vendor-specific behavior and keywords through {@link DBMetaData}.</li>
 *   <li>Provide metadata to {@link com.viaoa.datasource.jdbc.db.DataAccessObject}
 *       for result-set population.</li>
 * </ul>
 *
 * <h2>Thread-Safety</h2>
 * Schema models are immutable after load and safe for concurrent read access.
 *
 */
package com.viaoa.datasource.jdbc.db;