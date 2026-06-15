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
package com.viaoa.datasource.jdbc.db;

import com.viaoa.object.OAObjectKey;

/**
 * Represents a single row in a many-to-many (M:N) join table within the
 * OA JDBC metadata model.
 * <p>
 * A {@code ManyToMany} instance holds the pair of {@link OAObjectKey} values
 * that link two OAObjects through an intermediate join table. This structure
 * is used internally by {@code OADataSourceJDBC} when loading or persisting
 * many-to-many relationships.
 * </p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Store the left-side object key ({@link #ok1}).</li>
 *   <li>Store the right-side object key ({@link #ok2}).</li>
 *   <li>Provide a simple container structure representing a join-row mapping.</li>
 * </ul>
 */
public class ManyToMany {

	/**
	 * The {@link OAObjectKey} for the first object participating in the many-to-many
	 * relationship. Corresponds to one side of the join table row.
	 */
    public OAObjectKey ok1;

    /**
     * The {@link OAObjectKey} for the second object participating in the many-to-many
     * relationship. Represents the opposite side of the join table row.
     */
    public OAObjectKey ok2;

    /**
     * Constructs a representation of a single many-to-many join-table row.
     *
     * @param ok1 the left-side object key
     * @param ok2 the right-side object key
     */
    public ManyToMany(OAObjectKey ok1, OAObjectKey ok2) {
        this.ok1 = ok1;
        this.ok2 = ok2;
    }
}
