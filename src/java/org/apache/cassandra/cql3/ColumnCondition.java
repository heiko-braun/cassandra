/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3;

import java.nio.ByteBuffer;
import java.util.*;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterators;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.ColumnSlice;
import org.apache.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.db.marshal.CounterColumnType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.exceptions.InvalidRequestException;

/**
 * A CQL3 condition.
 */
public class ColumnCondition
{
    public final CFDefinition.Name column;
    private final Term value;

    private List<ByteBuffer> variables;

    private ColumnCondition(CFDefinition.Name column, Term value)
    {
        this.column = column;
        this.value = value;
    }

    // The only ones we support so far
    public static ColumnCondition equal(CFDefinition.Name column, Term value)
    {
        return new ColumnCondition(column, value);
    }

    // See CQL3CasConditions for why it's convenient to have this
    public ColumnCondition attach(List<ByteBuffer> variables)
    {
        this.variables = variables;
        return this;
    }

    /**
     * Collects the column specification for the bind variables of this operation.
     *
     * @param boundNames the list of column specification where to collect the
     * bind variables of this term in.
     */
    public void collectMarkerSpecification(VariableSpecifications boundNames)
    {
        value.collectMarkerSpecification(boundNames);
    }

    // Not overriding equals() because we need the variables to have been attached when this is
    // called and so having a non standard method name might help avoid mistakes
    public boolean equalsTo(ColumnCondition other) throws InvalidRequestException
    {
        return column.equals(other.column)
            && value.bindAndGet(variables).equals(other.value.bindAndGet(other.variables));
    }

    private ColumnNameBuilder copyOrUpdatePrefix(CFMetaData cfm, ColumnNameBuilder rowPrefix)
    {
        return column.kind == CFDefinition.Name.Kind.STATIC ? cfm.getStaticColumnNameBuilder() : rowPrefix.copy();
    }

    /**
     * Validates whether this condition applies to {@code current}.
     */
    public boolean appliesTo(ColumnNameBuilder rowPrefix, ColumnFamily current, long now) throws InvalidRequestException
    {
        if (column.type instanceof CollectionType)
            return collectionAppliesTo((CollectionType)column.type, rowPrefix, current, now);

        ColumnNameBuilder prefix = copyOrUpdatePrefix(current.metadata(), rowPrefix);
        ByteBuffer columnName = column.kind == CFDefinition.Name.Kind.VALUE_ALIAS
                              ? prefix.build()
                              : prefix.add(column.name.key).build();

        Column c = current.getColumn(columnName);
        ByteBuffer v = value.bindAndGet(variables);
        return v == null
             ? c == null || !c.isLive(now)
             : c != null && c.isLive(now) && c.value().equals(v);
    }

    private boolean collectionAppliesTo(CollectionType type, ColumnNameBuilder rowPrefix, ColumnFamily current, final long now) throws InvalidRequestException
    {
        ColumnNameBuilder collectionPrefix = copyOrUpdatePrefix(current.metadata(), rowPrefix).add(column.name.key);
        // We are testing for collection equality, so we need to have the expected values *and* only those.
        ColumnSlice[] collectionSlice = new ColumnSlice[]{ new ColumnSlice(collectionPrefix.build(), collectionPrefix.buildAsEndOfRange()) };
        // Filter live columns, this makes things simpler afterwards
        Iterator<Column> iter = Iterators.filter(current.iterator(collectionSlice), new Predicate<Column>()
        {
            public boolean apply(Column c)
            {
                // we only care about live columns
                return c.isLive(now);
            }
        });

        Term.Terminal v = value.bind(variables);
        if (v == null)
            return !iter.hasNext();

        switch (type.kind)
        {
            case LIST: return listAppliesTo(current.metadata(), iter, ((Lists.Value)v).elements);
            case SET: return setAppliesTo(current.metadata(), iter, ((Sets.Value)v).elements);
            case MAP: return mapAppliesTo(current.metadata(), iter, ((Maps.Value)v).map);
        }
        throw new AssertionError();
    }

    private static ByteBuffer collectionKey(CFMetaData cfm, Column c)
    {
        ByteBuffer[] bbs = ((CompositeType)cfm.comparator).split(c.name());
        return bbs[bbs.length - 1];
    }

    private boolean listAppliesTo(CFMetaData cfm, Iterator<Column> iter, List<ByteBuffer> elements)
    {
        for (ByteBuffer e : elements)
            if (!iter.hasNext() || iter.next().value().equals(e))
                return false;
        // We must not have more elements than expected
        return !iter.hasNext();
    }

    private boolean setAppliesTo(CFMetaData cfm, Iterator<Column> iter, Set<ByteBuffer> elements)
    {
        Set<ByteBuffer> remaining = new HashSet<>(elements);
        while (iter.hasNext())
        {
            if (remaining.isEmpty())
                return false;

            if (!remaining.remove(collectionKey(cfm, iter.next())))
                return false;
        }
        return remaining.isEmpty();
    }

    private boolean mapAppliesTo(CFMetaData cfm, Iterator<Column> iter, Map<ByteBuffer, ByteBuffer> elements)
    {
        Map<ByteBuffer, ByteBuffer> remaining = new HashMap<>(elements);
        while (iter.hasNext())
        {
            if (remaining.isEmpty())
                return false;

            Column c = iter.next();
            if (!remaining.remove(collectionKey(cfm, c)).equals(c.value()))
                return false;
        }
        return remaining.isEmpty();
    }

    public static class Raw
    {
        private final Term.Raw value;

        public Raw(Term.Raw value)
        {
            this.value = value;
        }

        public ColumnCondition prepare(CFDefinition.Name receiver) throws InvalidRequestException
        {
            if (receiver.type instanceof CounterColumnType)
                throw new InvalidRequestException("Condtions on counters are not supported");

            return ColumnCondition.equal(receiver, value.prepare(receiver));
        }
    }
}
