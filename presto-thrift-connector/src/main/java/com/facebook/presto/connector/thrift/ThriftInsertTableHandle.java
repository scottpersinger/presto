/*
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
package com.facebook.presto.connector.thrift;

import com.facebook.presto.common.type.Type;
import com.facebook.presto.spi.ConnectorInsertTableHandle;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public class ThriftInsertTableHandle
        implements ConnectorInsertTableHandle
{
    private final ThriftTableHandle table;
    private final List<Type> columnTypes;
    private final List<String> columnNames;

    @JsonCreator
    public ThriftInsertTableHandle(
            @JsonProperty("table") ThriftTableHandle table,
            @JsonProperty("columnTypes") List<Type> columnTypes,
            @JsonProperty("columnNames") List<String> columnNames)
    {
        this.table = requireNonNull(table, "table is null");
        this.columnTypes = ImmutableList.copyOf(columnTypes);
        this.columnNames = ImmutableList.copyOf(columnNames);
    }

    @JsonProperty
    public ThriftTableHandle getTable()
    {
        return table;
    }

    @JsonProperty
    public List<Type> getColumnTypes()
    {
        return columnTypes;
    }

    @JsonProperty
    public List<String> getColumnNames()
    {
        return columnNames;
    }
    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("table", table)
                .add("columnTypes", columnTypes)
                .add("columnNames", columnNames)
                .toString();
    }
}
