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

import java.lang.reflect.*;

import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.runtime.OARuntime;

/**
 * Represents a foreign-key relationship between two {@link Table} objects in the
 * OA JDBC metadata model.
 * <p>
 * A {@code Link} maps an OA reference property (for example, a Java getter such
 * as {@code getDept()}) to the corresponding database relationship. This includes
 * the target table, the foreign-key columns, and an optional reverse property
 * name for bidirectional navigation.
 * </p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Identify the referenced table.</li>
 *   <li>Store the foreign-key columns used to join tables.</li>
 *   <li>Resolve the reverse link for bidirectional relationships.</li>
 *   <li>Resolve the Java getter method representing the link.</li>
 * </ul>
 *
 * @see Table
 * @see Column
 */
public class Link {

	/**
	 * Name of the object reference property corresponding to this link.
	 * <p>
	 * For example, if the Java getter is {@code getDept()}, then the
	 * property name would be {@code "dept"}.
	 * </p>
	 */
	public String propertyName; 
    
	/**
	 * The destination {@link Table} referenced by this link. Represents the table
	 * that this object's foreign-key values map to.
	 */
    public Table toTable;
    
    /**
     * List of foreign-key {@link Column} objects used to join this table to the
     * referenced {@link #toTable}. Each entry maps to a component of the primary
     * key or foreign key being referenced.
     */
    public Column[] fkeys;  // foreign key columns that need to match pkey/fkey in toTable
    
    /**
     * Name of the reverse reference property in {@link #toTable} that represents
     * navigation back to this table. Used for establishing bidirectional links.
     */
    public String reversePropertyName;

    /**
     * Cached Java reflection {@link Method} reference for the getter corresponding
     * to {@link #propertyName}. Resolved lazily by {@link #getGetMethod()}.
     */
    Method methodGet;
    
    /**
     * The {@link Table} owning this link definition. Used to resolve supporting
     * metadata such as the Java class backing the table.
     */
    Table table;
    
    /**
     * Default constructor creating an empty {@code Link}. Callers are responsible
     * for populating reference names, foreign keys, and target table.
     */
    public Link() {
    }

    /**
     * Constructs a {@code Link} connecting this table to another table.
     *
     * @param propertyName the reference property name on this table
     * @param reversePropertyName the reverse reference name in the destination table
     * @param toTable the table that this link references
     */
    public Link(String propertyName,String reversePropertyName, Table toTable) {
        this.propertyName = propertyName;
        this.reversePropertyName = reversePropertyName;
        this.toTable = toTable;
    }

    /**
     * Retrieves the reverse {@code Link} from {@link #toTable} that points back to
     * the owning table. Used when constructing JOIN relationships or navigating
     * bidirectional references.
     *
     * @return the reverse {@code Link}, or {@code null} if none exists
     */
    public Link getReverseLink() {
        return toTable.getLink(reversePropertyName);
    }

    /**
     * Returns the Java getter method associated with {@link #propertyName}.
     * <p>
     * Resolution is performed lazily: the method is looked up when first accessed
     * using the support class provided by this link's owning {@link Table}.
     * Subsequent calls return the cached {@link Method}.
     * </p>
     *
     * @return the getter {@link Method}, or {@code null} if not found
     */
    public Method getGetMethod() {
        if (methodGet == null && table != null) {
            Class clazz = table.getSupportClass();
            if (clazz != null && propertyName != null && propertyName.length() != 0) {
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(clazz);
                methodGet = og.objectsInternal().callObjectInfoGetMethod(clazz, "get" + propertyName);
                //was: methodGet = OAReflect.getMethod(clazz, "get"+propertyName);
            }
        }
        return methodGet;
    }
}

