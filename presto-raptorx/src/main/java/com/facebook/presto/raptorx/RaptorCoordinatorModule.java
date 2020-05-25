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
package com.facebook.presto.raptorx;

import com.facebook.presto.raptorx.chunkstore.ChunkStoreCleaner;
import com.facebook.presto.raptorx.chunkstore.ChunkStoreCleanerConfig;
import com.facebook.presto.raptorx.metadata.CommitCleanerConfig;
import com.facebook.presto.raptorx.metadata.CommitCleanerJob;
import com.facebook.presto.raptorx.storage.BucketBalancer;
import com.facebook.presto.raptorx.storage.BucketBalancerConfig;
import com.facebook.presto.raptorx.transaction.TransactionWriter;
import com.google.inject.Binder;
import com.google.inject.Module;

import static io.airlift.configuration.ConfigBinder.configBinder;
import static org.weakref.jmx.ObjectNames.generatedNameOf;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class RaptorCoordinatorModule
        implements Module
{
    @Override
    public void configure(Binder binder)
    {
        newExporter(binder).export(TransactionWriter.class).as(generatedNameOf(TransactionWriter.class));

        configBinder(binder).bindConfig(CommitCleanerConfig.class);
        binder.bind(CommitCleanerJob.class).asEagerSingleton();

        configBinder(binder).bindConfig(ChunkStoreCleanerConfig.class);
        binder.bind(ChunkStoreCleaner.class).asEagerSingleton();
        newExporter(binder).export(ChunkStoreCleaner.class).as(generatedNameOf(ChunkStoreCleaner.class));

        configBinder(binder).bindConfig(BucketBalancerConfig.class);
        binder.bind(BucketBalancer.class).asEagerSingleton();
        newExporter(binder).export(BucketBalancer.class).as(generatedNameOf(BucketBalancer.class));
    }
}