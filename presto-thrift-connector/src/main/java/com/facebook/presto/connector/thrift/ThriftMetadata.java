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

import com.facebook.drift.TException;
import com.facebook.drift.client.DriftClient;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.TypeManager;
import com.facebook.presto.common.type.VarcharType;
import com.facebook.presto.connector.thrift.annotations.ForMetadataRefresh;
import com.facebook.presto.spi.*;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.connector.ConnectorOutputMetadata;
import com.facebook.presto.spi.statistics.ComputedStatistics;
import com.facebook.presto.thrift.api.connector.PrestoThriftNullableSchemaName;
import com.facebook.presto.thrift.api.connector.PrestoThriftNullableTableMetadata;
import com.facebook.presto.thrift.api.connector.PrestoThriftSchemaTableName;
import com.facebook.presto.thrift.api.connector.PrestoThriftService;
import com.facebook.presto.thrift.api.connector.PrestoThriftServiceException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.airlift.units.Duration;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static com.facebook.presto.connector.thrift.ThriftErrorCode.THRIFT_SERVICE_INVALID_RESPONSE;
import static com.facebook.presto.connector.thrift.util.ThriftExceptions.toPrestoException;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.StandardErrorCode.PERMISSION_DENIED;
import static com.google.common.cache.CacheLoader.asyncReloading;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.function.Function.identity;

public class ThriftMetadata
        implements ConnectorMetadata
{
    private static final Duration EXPIRE_AFTER_WRITE = new Duration(10, MINUTES);
    private static final Duration REFRESH_AFTER_WRITE = new Duration(2, MINUTES);

    private final DriftClient<PrestoThriftService> client;
    private final ThriftHeaderProvider thriftHeaderProvider;
    private final TypeManager typeManager;
    private final LoadingCache<SchemaTableName, Optional<ThriftTableMetadata>> tableCache;
    private final Map<SchemaTableName, Long> tableIds = new HashMap<>();

    @Inject
    public ThriftMetadata(
            DriftClient<PrestoThriftService> client,
            ThriftHeaderProvider thriftHeaderProvider,
            TypeManager typeManager,
            @ForMetadataRefresh Executor metadataRefreshExecutor)
    {
        this.client = requireNonNull(client, "client is null");
        this.thriftHeaderProvider = requireNonNull(thriftHeaderProvider, "thriftHeaderProvider is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.tableCache = CacheBuilder.newBuilder()
                .expireAfterWrite(EXPIRE_AFTER_WRITE.toMillis(), MILLISECONDS)
                .refreshAfterWrite(REFRESH_AFTER_WRITE.toMillis(), MILLISECONDS)
                .build(asyncReloading(CacheLoader.from(this::getTableMetadataInternal), metadataRefreshExecutor));
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        try {
            return client.get(thriftHeaderProvider.getHeaders(session)).listSchemaNames();
        }
        catch (PrestoThriftServiceException | TException e) {
            throw toPrestoException(e);
        }
    }

    @Override
    public ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        return tableCache.getUnchecked(tableName)
                .map(ThriftTableMetadata::getSchemaTableName)
                .map(ThriftTableHandle::new)
                .orElse(null);
    }

    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(
            ConnectorSession session,
            ConnectorTableHandle table,
            Constraint<ColumnHandle> constraint,
            Optional<Set<ColumnHandle>> desiredColumns)
    {
        ThriftTableHandle tableHandle = (ThriftTableHandle) table;
        ThriftTableLayoutHandle layoutHandle = new ThriftTableLayoutHandle(
                tableHandle.getSchemaName(),
                tableHandle.getTableName(),
                desiredColumns,
                constraint.getSummary());
        return ImmutableList.of(new ConnectorTableLayoutResult(new ConnectorTableLayout(layoutHandle), constraint.getSummary()));
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle handle)
    {
        return new ConnectorTableLayout(handle);
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ThriftTableHandle handle = ((ThriftTableHandle) tableHandle);
        return getRequiredTableMetadata(new SchemaTableName(handle.getSchemaName(), handle.getTableName())).toConnectorTableMetadata();
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, String schemaNameOrNull)
    {
        try {
            return client.get(thriftHeaderProvider.getHeaders(session)).listTables(new PrestoThriftNullableSchemaName(schemaNameOrNull)).stream()
                    .map(PrestoThriftSchemaTableName::toSchemaTableName)
                    .collect(toImmutableList());
        }
        catch (PrestoThriftServiceException | TException e) {
            throw toPrestoException(e);
        }
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return getTableMetadata(session, tableHandle).getColumns().stream().collect(toImmutableMap(ColumnMetadata::getName, ThriftColumnHandle::new));
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        return ((ThriftColumnHandle) columnHandle).toColumnMetadata();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        return listTables(session, prefix.getSchemaName()).stream().collect(toImmutableMap(identity(), schemaTableName -> getRequiredTableMetadata(schemaTableName).getColumns()));
    }

    @Override
    public Optional<ConnectorResolvedIndex> resolveIndex(ConnectorSession session, ConnectorTableHandle tableHandle, Set<ColumnHandle> indexableColumns, Set<ColumnHandle> outputColumns, TupleDomain<ColumnHandle> tupleDomain)
    {
        ThriftTableHandle table = (ThriftTableHandle) tableHandle;
        ThriftTableMetadata tableMetadata = getRequiredTableMetadata(new SchemaTableName(table.getSchemaName(), table.getTableName()));
        if (tableMetadata.containsIndexableColumns(indexableColumns)) {
            return Optional.of(new ConnectorResolvedIndex(new ThriftIndexHandle(tableMetadata.getSchemaTableName(), tupleDomain, session), tupleDomain));
        }
        else {
            return Optional.empty();
        }
    }

    private ThriftTableMetadata getRequiredTableMetadata(SchemaTableName schemaTableName)
    {
        Optional<ThriftTableMetadata> table = tableCache.getUnchecked(schemaTableName);
        if (!table.isPresent()) {
            throw new TableNotFoundException(schemaTableName);
        }
        else {
            return table.get();
        }
    }

    // this method makes actual thrift request and should be called only by cache load method
    private Optional<ThriftTableMetadata> getTableMetadataInternal(SchemaTableName schemaTableName)
    {
        requireNonNull(schemaTableName, "schemaTableName is null");
        PrestoThriftNullableTableMetadata thriftTableMetadata = getTableMetadata(schemaTableName);
        if (thriftTableMetadata.getTableMetadata() == null) {
            return Optional.empty();
        }
        ThriftTableMetadata tableMetadata = new ThriftTableMetadata(thriftTableMetadata.getTableMetadata(), typeManager);
        if (!Objects.equals(schemaTableName, tableMetadata.getSchemaTableName())) {
            throw new PrestoException(THRIFT_SERVICE_INVALID_RESPONSE, "Requested and actual table names are different");
        }
        return Optional.of(tableMetadata);
    }

    private PrestoThriftNullableTableMetadata getTableMetadata(SchemaTableName schemaTableName)
    {
        // treat invalid names as not found
        PrestoThriftSchemaTableName name;
        try {
            name = new PrestoThriftSchemaTableName(schemaTableName);
        }
        catch (IllegalArgumentException e) {
            return new PrestoThriftNullableTableMetadata(null);
        }

        try {
            return client.get().getTableMetadata(name);
        }
        catch (PrestoThriftServiceException | TException e) {
            throw toPrestoException(e);
        }
    }

    // start inserting data into an existing table
    @Override
    public synchronized ThriftInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ThriftTableHandle thriftTableHandle = (ThriftTableHandle) tableHandle;
        // This needs to construct a ThriftInsertTableHandle which contains the
        // column names and types of the target table. We will need these to unpack
        // the Page blocks we receive on the PageSink.
        ConnectorTableMetadata metadata = getTableMetadata(session, tableHandle);
        ArrayList<Type> columnTypes = new ArrayList<Type>();
        ArrayList<String> columnNames = new ArrayList<String>();
        for (int i = 0; i < metadata.getColumns().size(); i++) {
            columnTypes.add(metadata.getColumns().get(i).getType());
            columnNames.add(metadata.getColumns().get(i).getName());
        }

        return new ThriftInsertTableHandle(thriftTableHandle, columnTypes, columnNames);
    }

    @Override
    public synchronized Optional<ConnectorOutputMetadata> finishInsert(
            ConnectorSession session,
            ConnectorInsertTableHandle insertHandle,
            Collection<Slice> fragments,
            Collection<ComputedStatistics> computedStatistics)
    {
        requireNonNull(insertHandle, "insertHandle is null");
        ThriftInsertTableHandle thriftInsertHandle = (ThriftInsertTableHandle) insertHandle;

        //updateRowsOnHosts(memoryInsertHandle.getTable(), fragments);
        return Optional.empty();
    }

    // start creation of a new table with data
    @Override
    public synchronized ThriftInsertTableHandle beginCreateTable(
            ConnectorSession session,
            ConnectorTableMetadata tableMetadata,
            Optional<ConnectorNewTableLayout> layout)
    {
        ThriftTableHandle tableHandle = new ThriftTableHandle(
                tableMetadata.getTable().getSchemaName(),
                tableMetadata.getTable().getTableName());

                    ArrayList<Type> columnTypes = new ArrayList<Type>();
        ArrayList<String> columnNames = new ArrayList<String>();
        for (int i = 0; i < tableMetadata.getColumns().size(); i++) {
            columnTypes.add(tableMetadata.getColumns().get(i).getType());
            columnNames.add(tableMetadata.getColumns().get(i).getName());
        }

        return new ThriftInsertTableHandle(tableHandle, columnTypes, columnNames);
    }

    @Override
    public synchronized Optional<ConnectorOutputMetadata> finishCreateTable(
            ConnectorSession session,
            ConnectorOutputTableHandle tableHandle,
            Collection<Slice> fragments,
            Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ThriftTableHandle thriftTableHandle = (ThriftTableHandle) tableHandle;
        try {
            ;
            client.get(thriftHeaderProvider.getHeaders(session)).
                    dropTable(new PrestoThriftSchemaTableName(
                            thriftTableHandle.getSchemaName(),
                            thriftTableHandle.getTableName()
                    )
            );
        }
        catch (PrestoThriftServiceException | TException e) {
            throw toPrestoException(e);
        }
    }


    /**
     * Get the column handle that will generate row IDs for the delete operation.
     * These IDs will be passed to the {@code deleteRows()} method of the
     * {@link com.facebook.presto.spi.UpdatablePageSource} that created them.
     */
    @Override
    public ColumnHandle getUpdateRowIdColumnHandle(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        // return the first key column from the table metadata
        ThriftTableHandle tHandle = (ThriftTableHandle)tableHandle;
        ThriftTableMetadata metadata = getRequiredTableMetadata(
                new SchemaTableName(tHandle.getSchemaName(), tHandle.getTableName())
        );

        String keyCol = "keyColumn";
        Object[] keys = metadata.getIndexableKeys().toArray();
        if (keys.length > 0) {
            Set<String> keyCols = (Set<String>)keys[0];
            if (keyCols.size() > 0) {
                keyCol = (String)keyCols.toArray()[0];
            }
        }
        Type keyColType = VarcharType.VARCHAR;
        for (int i = 0; i < metadata.getColumns().size(); i++) {
            if (metadata.getColumns().get(i).getName().equals(keyCol)) {
                keyColType = metadata.getColumns().get(i).getType();
            }
        }
        return new ThriftColumnHandle(
                "keyColumn",
                keyColType,
                null,
                false);
    }

    /**
     * Begin delete query
     */
    @Override
    public ConnectorTableHandle beginDelete(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return tableHandle;
    }

    /**
     * Finish delete query
     *
     * @param fragments all fragments returned by {@link com.facebook.presto.spi.UpdatablePageSource#finish()}
     */
    @Override
    public void finishDelete(ConnectorSession session, ConnectorTableHandle tableHandle, Collection<Slice> fragments)
    {
    }


}
