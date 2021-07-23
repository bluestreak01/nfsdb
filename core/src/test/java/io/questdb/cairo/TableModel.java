/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.cairo.vm.CMARWMemoryImpl;
import io.questdb.cairo.vm.api.MARWMemory;
import io.questdb.std.*;
import io.questdb.std.str.Path;

import java.io.Closeable;

public class TableModel implements TableStructure, Closeable {
    private static final long COLUMN_FLAG_CACHED = 1L;
    private static final long COLUMN_FLAG_INDEXED = 2L;
    private final String name;
    private final int partitionBy;
    private final MARWMemory mem = new CMARWMemoryImpl();
    private final ObjList<CharSequence> columnNames = new ObjList<>();
    private final LongList columnBits = new LongList();
    private final Path path = new Path();
    private final CairoConfiguration cairoCfg;
    private int timestampIndex = -1;

    public TableModel(CairoConfiguration cairoCfg, String name, int partitionBy) {
        this.cairoCfg = cairoCfg;
        this.name = name;
        this.partitionBy = partitionBy;
    }

    public TableModel cached(boolean cached) {
        int last = columnBits.size() - 1;
        assert last > 0;
        assert ((int) columnBits.getQuick(last - 1) == ColumnType.SYMBOL);
        long bits = columnBits.getQuick(last);
        if (cached) {
            columnBits.setQuick(last, bits | COLUMN_FLAG_CACHED);
        } else {
            columnBits.setQuick(last, bits & ~COLUMN_FLAG_CACHED);
        }
        return this;
    }

    @Override
    public void close() {
        Misc.free(mem);
        Misc.free(path);
    }

    public TableModel col(CharSequence name, int type) {
        columnNames.add(Chars.toString(name));
        // set default symbol capacity
        columnBits.add((128L << 32) | type);
        columnBits.add(COLUMN_FLAG_CACHED);
        return this;
    }

    @Override
    public boolean isSequential(int columnIndex) {
        return false;
    }

    @Override
    public boolean getSymbolCacheFlag(int index) {
        return (columnBits.getQuick(index * 2 + 1) & COLUMN_FLAG_CACHED) == COLUMN_FLAG_CACHED;
    }

    public CairoConfiguration getCairoCfg() {
        return cairoCfg;
    }

    @Override
    public int getColumnCount() {
        return columnNames.size();
    }

    @Override
    public CharSequence getColumnName(int index) {
        return columnNames.getQuick(index);
    }

    @Override
    public int getColumnType(int index) {
        return (int) columnBits.getQuick(index * 2);
    }

    @Override
    public int getIndexBlockCapacity(int index) {
        return (int) (columnBits.getQuick(index * 2 + 1) >> 32);
    }

    @Override
    public boolean isIndexed(int index) {
        return (columnBits.getQuick(index * 2 + 1) & COLUMN_FLAG_INDEXED) == COLUMN_FLAG_INDEXED;
    }

    public MARWMemory getMem() {
        return mem;
    }

    public String getName() {
        return name;
    }

    @Override
    public int getPartitionBy() {
        return partitionBy;
    }

    public Path getPath() {
        return path;
    }

    @Override
    public CharSequence getTableName() {
        return name;
    }

    @Override
    public int getSymbolCapacity(int index) {
        return (int) (columnBits.getQuick(index * 2) >> 32);
    }

    @Override
    public int getTimestampIndex() {
        return timestampIndex;
    }

    public TableModel indexed(boolean indexFlag, int indexBlockCapacity) {
        int pos = columnBits.size() - 1;
        assert pos > 0;
        long bits = columnBits.getQuick(pos);
        if (indexFlag) {
            assert indexBlockCapacity > 1;
            columnBits.setQuick(pos, bits | ((long) Numbers.ceilPow2(indexBlockCapacity) << 32) | COLUMN_FLAG_INDEXED);
        } else {
            columnBits.setQuick(pos, bits & ~COLUMN_FLAG_INDEXED);
        }
        return this;
    }

    public TableModel symbolCapacity(int capacity) {
        int pos = columnBits.size() - 2;
        assert pos > -1;
        long bits = columnBits.getQuick(pos);
        assert ((int) bits == ColumnType.SYMBOL);
        bits = (((long) capacity) << 32) | (int) bits;
        columnBits.setQuick(pos, bits);
        return this;
    }

    public TableModel timestamp() {
        return timestamp("timestamp");
    }

    public TableModel timestamp(CharSequence name) {
        assert timestampIndex == -1;
        timestampIndex = columnNames.size();
        col(name, ColumnType.TIMESTAMP);
        return this;
    }

    @Override
    public int getMaxUncommittedRows() {
        return cairoCfg.getMaxUncommittedRows();
    }

    @Override
    public long getCommitLag() {
        return cairoCfg.getCommitLag();
    }
}
