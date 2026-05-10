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

/**
 * Represents a database index definition within the OA JDBC metadata model.
 * <p>
 * An {@code Index} identifies one or more column names that participate in the
 * index and optionally marks whether the index was created to support a foreign
 * key relationship. Instances of this class are used by schema utilities and
 * database generators when analyzing or constructing DDL.
 * </p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Store the index name.</li>
 *   <li>List one or more column names included in the index.</li>
 *   <li>Indicate whether the index exists for a foreign key.</li>
 * </ul>
 *
 * @see Table
 * @see Column
 */
public class Index {

	/**
	 * The name of the index as defined in the database schema.
	 */
	public String name;
	
	/**
	 * The column or columns participating in this index. The order of column
	 * names follows the index definition in the database.
	 */
	public String[] columns;
	
	/**
	 * Flag indicating whether this index was created to support a foreign key.
	 * Some databases automatically generate such indexes.
	 */
	public boolean fkey; // is this index for an foreign key (note: some DBs auto create indexes for fkeys)

	/**
	 * Creates an {@code Index} with the given name and list of columns.
	 *
	 * @param name the index name
	 * @param columns one or more column names included in the index
	 */
    public Index(String name, String[] columns) {
    	this.name = name;
    	this.columns = columns;
    }

    /**
     * Creates an {@code Index} with the given name, columns, and a flag
     * identifying whether it was created for a foreign key.
     *
     * @param name the index name
     * @param columns the indexed column names
     * @param fkey {@code true} if the index is for a foreign key
     */
    public Index(String name, String[] columns, boolean fkey) {
        this.name = name;
        this.columns = columns;
        this.fkey = fkey;
    }
    
    /**
     * Convenience constructor for defining an index on a single column.
     *
     * @param name the index name
     * @param column the indexed column name
     */
    public Index(String name, String column) {
    	this.name = name;
    	this.columns = new String[] { column };
    }
    
    /**
     * Convenience constructor for defining a single-column index that may
     * have been created to support a foreign key relationship.
     *
     * @param name the index name
     * @param column the indexed column name
     * @param fkey {@code true} if the index is for a foreign key
     */
    public Index(String name, String column, boolean fkey) {
        this.name = name;
        this.columns = new String[] { column };
        this.fkey = fkey;
    }
}

