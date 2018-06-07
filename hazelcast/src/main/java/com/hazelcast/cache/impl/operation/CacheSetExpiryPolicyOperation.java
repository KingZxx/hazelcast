/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.cache.impl.operation;

import com.hazelcast.cache.impl.CacheDataSerializerHook;
import com.hazelcast.cache.impl.CacheEntryViews;
import com.hazelcast.cache.impl.ICacheRecordStore;
import com.hazelcast.cache.impl.ICacheService;
import com.hazelcast.cache.impl.event.CacheWanEventPublisher;
import com.hazelcast.cache.impl.record.CacheRecord;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.BackupAwareOperation;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.PartitionAwareOperation;
import com.hazelcast.spi.ServiceNamespace;
import com.hazelcast.spi.ServiceNamespaceAware;
import com.hazelcast.spi.impl.AbstractNamedOperation;
import com.hazelcast.spi.impl.MutatingOperation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CacheSetExpiryPolicyOperation extends AbstractNamedOperation
        implements IdentifiedDataSerializable, PartitionAwareOperation, ServiceNamespaceAware, MutableOperation,
        MutatingOperation, BackupAwareOperation {

    private transient ICacheService service;
    private transient ICacheRecordStore recordStore;
    private transient int partitionId;

    private List<Data> keys;
    private Data expiryPolicy;
    private int completionId;

    public CacheSetExpiryPolicyOperation() {

    }

    public CacheSetExpiryPolicyOperation(String name, List<Data> keys, Data expiryPolicy) {
        super(name);
        this.keys = keys;
        this.expiryPolicy = expiryPolicy;
    }

    public CacheSetExpiryPolicyOperation(String name, List<Data> keys, Data expiryPolicy, int completionId) {
        this(name, keys, expiryPolicy);
        this.completionId = completionId;
    }

    @Override
    public void beforeRun() throws Exception {
        super.beforeRun();
        service = getService();
        partitionId = getPartitionId();
        recordStore = service.getRecordStore(name, partitionId);
    }

    @Override
    public void run() throws Exception {
        if (recordStore == null) {
            return;
        }
        recordStore.setExpiryPolicy(keys, expiryPolicy, getCallerUuid(), completionId);
    }

    @Override
    public void afterRun() throws Exception {
        super.afterRun();
        if (recordStore.isWanReplicationEnabled()) {
            CacheWanEventPublisher publisher = service.getCacheWanEventPublisher();
            for (Data key : keys) {
                CacheRecord record = recordStore.getRecord(key);
                publisher.publishWanReplicationUpdate(name, CacheEntryViews.createEntryView(key, record));
            }
        }
    }

    @Override
    public int getFactoryId() {
        return CacheDataSerializerHook.F_ID;
    }

    @Override
    public int getId() {
        return CacheDataSerializerHook.SET_EXPIRY_POLICY;
    }

    @Override
    public int getCompletionId() {
        return completionId;
    }

    @Override
    public void setCompletionId(int completionId) {
        this.completionId = completionId;
    }

    @Override
    public ServiceNamespace getServiceNamespace() {
        ICacheRecordStore store = recordStore;
        if (store == null) {
            store = service.getOrCreateRecordStore(name, partitionId);
        }
        return store.getObjectNamespace();
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeInt(keys.size());
        for (Data key: keys) {
            out.writeData(key);
        }
        out.writeInt(completionId);
        out.writeData(expiryPolicy);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        int s = in.readInt();
        keys = new ArrayList<Data>();
        while (s-- > 0) {
            keys.add(in.readData());
        }
        completionId = in.readInt();
        expiryPolicy = in.readData();
    }

    @Override
    public boolean shouldBackup() {
        return recordStore.getConfig().getTotalBackupCount() > 0;
    }

    @Override
    public int getSyncBackupCount() {
        return recordStore.getConfig().getBackupCount();
    }

    @Override
    public int getAsyncBackupCount() {
        return recordStore.getConfig().getAsyncBackupCount();
    }

    @Override
    public Operation getBackupOperation() {
        return new CacheSetExpiryPolicyBackupOperation(name, keys, expiryPolicy);
    }
}