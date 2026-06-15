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
 * Provides the JDBC implementation of the OADataSource abstraction.
 * <p>
 * Classes in this package translate OA object queries and filters into native SQL commands
 * and manage all JDBC interactions — including connection pooling, statement preparation,
 * transaction management, and schema generation.
 * <p>
 * The central class {@link com.viaoa.datasource.jdbc.OADataSourceJDBC} implements the
 * {@link com.viaoa.datasource.OADataSourceInterface}, serving as the concrete bridge between
 * the OA Object Graph and relational databases.
 * <p>
 * Subpackages include:
 * <ul>
 *   <li>{@code query} — query tokenization and parsing utilities</li>
 *   <li>{@code meta} — database metadata reflection and schema helpers</li>
 * </ul>
 * <p>
 * For developers implementing custom persistence layers, this package
 * illustrates how OA’s object-centric API can be bound to standard JDBC.
 */
package com.viaoa.datasource.jdbc;
