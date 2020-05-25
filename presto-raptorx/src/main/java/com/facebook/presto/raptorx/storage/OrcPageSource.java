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
package com.facebook.presto.raptorx.storage;

import com.facebook.presto.memory.context.AggregatedMemoryContext;
import com.facebook.presto.orc.OrcDataSource;
import com.facebook.presto.orc.OrcRecordReader;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.UpdatablePageSource;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.LazyBlock;
import com.facebook.presto.spi.block.LazyBlockLoader;
import com.facebook.presto.spi.block.RunLengthEncodedBlock;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;

import java.io.IOException;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.facebook.presto.orc.OrcReader.MAX_BATCH_SIZE;
import static com.facebook.presto.raptorx.RaptorErrorCode.RAPTOR_STORAGE_ERROR;
import static com.facebook.presto.raptorx.util.Closeables.closeWithSuppression;
import static com.facebook.presto.spi.predicate.Utils.nativeValueToBlock;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

public class OrcPageSource
        implements UpdatablePageSource
{
    public static final int NULL_COLUMN = -1;
    public static final int ROW_ID_COLUMN = -2;
    public static final int CHUNK_ID_COLUMN = -3;
    public static final int BUCKET_NUMBER_COLUMN = -4;

    private final Optional<ChunkRewriter> chunkRewriter;

    private final OrcRecordReader recordReader;
    private final OrcDataSource orcDataSource;

    private final BitSet rowsToDelete;

    private final List<Long> columnIds;
    private final List<Type> types;

    private final Block[] constantBlocks;
    private final int[] columnIndexes;

    private final AggregatedMemoryContext systemMemoryContext;

    private int batchId;
    private boolean closed;

    public OrcPageSource(
            Optional<ChunkRewriter> chunkRewriter,
            OrcRecordReader recordReader,
            OrcDataSource orcDataSource,
            List<Long> columnIds,
            List<Type> columnTypes,
            List<Integer> columnIndexes,
            long chunkId,
            int bucketNumber,
            AggregatedMemoryContext systemMemoryContext)
    {
        this.chunkRewriter = requireNonNull(chunkRewriter, "chunkRewriter is null");
        this.recordReader = requireNonNull(recordReader, "recordReader is null");
        this.orcDataSource = requireNonNull(orcDataSource, "orcDataSource is null");

        this.rowsToDelete = new BitSet(toIntExact(recordReader.getFileRowCount()));

        checkArgument(columnIds.size() == columnTypes.size(), "ids and types mismatch");
        checkArgument(columnIds.size() == columnIndexes.size(), "ids and indexes mismatch");
        int size = columnIds.size();

        this.columnIds = ImmutableList.copyOf(columnIds);
        this.types = ImmutableList.copyOf(columnTypes);

        this.constantBlocks = new Block[size];
        this.columnIndexes = new int[size];

        for (int i = 0; i < size; i++) {
            this.columnIndexes[i] = columnIndexes.get(i);
            if (this.columnIndexes[i] == NULL_COLUMN) {
                constantBlocks[i] = buildSingleValueBlock(columnTypes.get(i), null);
            }
            else if (this.columnIndexes[i] == CHUNK_ID_COLUMN) {
                constantBlocks[i] = buildSingleValueBlock(columnTypes.get(i), chunkId);
            }
            else if (this.columnIndexes[i] == BUCKET_NUMBER_COLUMN) {
                constantBlocks[i] = buildSingleValueBlock(columnTypes.get(i), (long) bucketNumber);
            }
        }

        this.systemMemoryContext = requireNonNull(systemMemoryContext, "systemMemoryContext is null");
    }
    @Override
    public long getCompletedBytes()
    {
        return orcDataSource.getReadBytes();
    }

    @Override
    public long getReadTimeNanos()
    {
        return orcDataSource.getReadTimeNanos();
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return systemMemoryContext.getBytes();
    }

    @Override
    public boolean isFinished()
    {
        return closed;
    }

    @Override
    public Page getNextPage()
    {
        try {
            batchId++;
            int batchSize = recordReader.nextBatch();
            if (batchSize <= 0) {
                close();
                return null;
            }
            long filePosition = recordReader.getFilePosition();

            Block[] blocks = new Block[columnIndexes.length];
            for (int fieldId = 0; fieldId < blocks.length; fieldId++) {
                Type type = types.get(fieldId);
                if (constantBlocks[fieldId] != null) {
                    blocks[fieldId] = constantBlocks[fieldId].getRegion(0, batchSize);
                }
                else if (columnIndexes[fieldId] == ROW_ID_COLUMN) {
                    blocks[fieldId] = buildSequenceBlock(filePosition, batchSize);
                }
                else {
                    blocks[fieldId] = new LazyBlock(batchSize, new OrcBlockLoader(columnIndexes[fieldId], type));
                }
            }

            return new Page(batchSize, blocks);
        }
        catch (IOException | RuntimeException e) {
            closeWithSuppression(e, this);
            throw new PrestoException(RAPTOR_STORAGE_ERROR, e);
        }
    }

    @Override
    public void close()
    {
        closed = true;

        try {
            recordReader.close();
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_STORAGE_ERROR, e);
        }
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("columnNames", columnIds)
                .add("types", types)
                .toString();
    }

    @Override
    public void deleteRows(Block rowIds)
    {
        for (int i = 0; i < rowIds.getPositionCount(); i++) {
            long rowId = BIGINT.getLong(rowIds, i);
            rowsToDelete.set(toIntExact(rowId));
        }
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        checkState(chunkRewriter.isPresent(), "chunkRewriter is missing");
        return chunkRewriter.get().rewrite(rowsToDelete);
    }

    private static Block buildSequenceBlock(long start, int count)
    {
        BlockBuilder builder = BIGINT.createFixedSizeBlockBuilder(count);
        for (int i = 0; i < count; i++) {
            BIGINT.writeLong(builder, start + i);
        }
        return builder.build();
    }

    private static Block buildSingleValueBlock(Type type, Object value)
    {
        Block block = nativeValueToBlock(type, value);
        return new RunLengthEncodedBlock(block, MAX_BATCH_SIZE);
    }

    private final class OrcBlockLoader
            implements LazyBlockLoader<LazyBlock>
    {
        private final int expectedBatchId = batchId;
        private final int columnIndex;
        private final Type type;
        private boolean loaded;

        public OrcBlockLoader(int columnIndex, Type type)
        {
            this.columnIndex = columnIndex;
            this.type = requireNonNull(type, "type is null");
        }

        @Override
        public void load(LazyBlock lazyBlock)
        {
            if (loaded) {
                return;
            }

            checkState(batchId == expectedBatchId);

            try {
                Block block = recordReader.readBlock(type, columnIndex);
                lazyBlock.setBlock(block);
            }
            catch (IOException e) {
                throw new PrestoException(RAPTOR_STORAGE_ERROR, e);
            }

            loaded = true;
        }
    }
}