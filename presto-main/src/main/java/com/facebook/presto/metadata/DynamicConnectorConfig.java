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
package com.facebook.presto.metadata;

import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;

import java.util.Objects;

public class DynamicConnectorConfig
{
    private ObjectId id;
    @BsonProperty(value = "user_id")
    private String userId;
    private String connectorName;
    private String catalogName;

    public ObjectId getId()
    {
        return id;
    }

    public DynamicConnectorConfig setId(ObjectId id)
    {
        this.id = id;
        return this;
    }

    public String getUserId()
    {
        return userId;
    }

    public DynamicConnectorConfig setUserId(String userId)
    {
        this.userId = userId;
        return this;
    }

    public String getConnectorName()
    {
        return connectorName;
    }

    public DynamicConnectorConfig setConnectorName(String connectorName)
    {
        this.connectorName = connectorName;
        return this;
    }

    public String getCatalogName()
    {
        return catalogName;
    }

    public DynamicConnectorConfig setCatalogName(String catalogName)
    {
        this.catalogName = catalogName;
        return this;
    }

    @Override
    public String toString()
    {
        final StringBuffer sb = new StringBuffer("DynamicConnectorConfig{");
        sb.append("id=").append(id);
        sb.append(", user_id=").append(userId);
        sb.append(", connectorName=").append(connectorName);
        sb.append(", catalogName=").append(catalogName);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DynamicConnectorConfig cc = (DynamicConnectorConfig) o;
        return Objects.equals(id, cc.id) && Objects.equals(userId, cc.getUserId()) &&
                Objects.equals(connectorName, cc.getConnectorName()) &&
                Objects.equals(catalogName, cc.getCatalogName());
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(id, userId, connectorName, catalogName);
    }
}
