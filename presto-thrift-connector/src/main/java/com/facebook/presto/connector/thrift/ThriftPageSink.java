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
import com.facebook.presto.common.Page;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.spi.ConnectorInsertTableHandle;
import com.facebook.presto.spi.ConnectorOutputTableHandle;
import com.facebook.presto.spi.ConnectorPageSink;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.thrift.api.connector.PrestoThriftPageResult;
import com.facebook.presto.thrift.api.connector.PrestoThriftSchemaTableName;
import com.facebook.presto.thrift.api.connector.PrestoThriftService;
import com.facebook.presto.thrift.api.connector.PrestoThriftServiceException;
import com.facebook.presto.thrift.api.datatypes.PrestoThriftBlock;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class ThriftPageSink
        implements ConnectorPageSink
{
    private final PrestoThriftService client;
    private final List<Type> columnTypes;
    private final List<String> columnNames;
    private final ThriftInsertTableHandle tableHandle;

    // Get a page sink for inserting into an existing table
    public ThriftPageSink(DriftClient<PrestoThriftService> client,
                          Map<String, String> thriftHeader,
                          ConnectorInsertTableHandle insertTableHandle)
    {
        // init client
        requireNonNull(client, "client is null");
        this.client = client.get(thriftHeader);
        this.tableHandle = (ThriftInsertTableHandle) insertTableHandle;
        this.columnTypes = tableHandle.getColumnTypes();
        this.columnNames = tableHandle.getColumnNames();
    }

    // Get a page sink for inserting into a new table
    public ThriftPageSink(DriftClient<PrestoThriftService> client,
                          Map<String, String> thriftHeader,
                          ConnectorOutputTableHandle outputTableHandle)
    {
        // init client
        requireNonNull(client, "client is null");
        this.client = client.get(thriftHeader);
        this.tableHandle = (ThriftInsertTableHandle) outputTableHandle;
        this.columnTypes = tableHandle.getColumnTypes();
        this.columnNames = tableHandle.getColumnNames();
    }

    @Override
    public CompletableFuture<?> appendPage(Page page)
    {
        //pagesStore.add(tableId, page);
        //addedRows += page.getPositionCount();
        System.out.println("Inserting a page on thrift connector: " + page.toString());
        ArrayList<PrestoThriftBlock> columnBlocks = new ArrayList<PrestoThriftBlock>();
        for (int i = 0; i < page.getChannelCount(); i++) {
            columnBlocks.add(PrestoThriftBlock.fromBlock(page.getBlock(i), columnTypes.get(i)));
        }
        int rowCount = page.getPositionCount();
        PrestoThriftPageResult pageData = new PrestoThriftPageResult(columnBlocks, rowCount, null);

        try {
            PrestoThriftSchemaTableName schemaTableName =
                    new PrestoThriftSchemaTableName(
                            tableHandle.getTable().getSchemaName(),
                            tableHandle.getTable().getTableName()
                    );
            List<String> colTypes = columnTypes.stream().map(Type::getDisplayName).collect(Collectors.toList());
            long count = client.writeRows(schemaTableName, columnNames, colTypes, pageData);
        }
        catch (PrestoThriftServiceException e) {
            throw new PrestoException(ThriftErrorCode.THRIFT_SERVICE_GENERIC_REMOTE_ERROR, e);
        }
        catch (TException e) {
            throw new PrestoException(ThriftErrorCode.THRIFT_SERVICE_GENERIC_REMOTE_ERROR, e);
        }
        return NOT_BLOCKED;
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        return completedFuture(ImmutableList.of(Slices.EMPTY_SLICE));
    }

    @Override
    public void abort()
    {
    }
}
