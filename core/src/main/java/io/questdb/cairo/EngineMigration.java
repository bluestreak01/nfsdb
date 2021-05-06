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

import io.questdb.cairo.vm.MappedReadWriteMemory;
import io.questdb.cairo.vm.PagedMappedReadWriteMemory;
import io.questdb.cairo.vm.PagedVirtualMemory;
import io.questdb.cairo.vm.ReadWriteVirtualMemory;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.Timestamps;
import io.questdb.std.str.NativeLPSZ;
import io.questdb.std.str.Path;

import static io.questdb.cairo.ColumnType.VERSION_THAT_ADDED_TABLE_ID;
import static io.questdb.cairo.TableUtils.*;

public class EngineMigration {
    public static final int VERSION_TX_STRUCT_UPDATE_1 = 418;
    public static final int VERSION_TBL_META_HYSTERESIS = 419;

    // All offsets hardcoded here in case TableUtils offset calculation changes
    // in future code version
    public static final long TX_STRUCT_UPDATE_1_OFFSET_MAP_WRITER_COUNT = 72;
    public static final long TX_STRUCT_UPDATE_1_META_OFFSET_PARTITION_BY = 4;
    public static final String TX_STRUCT_UPDATE_1_ARCHIVE_FILE_NAME = "_archive";

    private static final Log LOG = LogFactory.getLog(EngineMigration.class);
    private static final ObjList<MigrationAction> MIGRATIONS = new ObjList<>();
    private static final IntList MIGRATIONS_CRITICALITY = new IntList();
    private static final int MIGRATIONS_LIST_OFFSET = VERSION_THAT_ADDED_TABLE_ID;
    private final CairoEngine engine;
    private final CairoConfiguration configuration;
    private boolean updateSuccess;

    public EngineMigration(CairoEngine engine, CairoConfiguration configuration) {
        this.engine = engine;
        this.configuration = configuration;
    }

    public void migrateEngineTo(int latestVersion) {
        final FilesFacade ff = configuration.getFilesFacade();
        int tempMemSize = 8;
        long mem = Unsafe.malloc(tempMemSize);

        try (PagedVirtualMemory virtualMem = new PagedVirtualMemory(ff.getPageSize(), 8);
             Path path = new Path();
             PagedMappedReadWriteMemory rwMemory = new PagedMappedReadWriteMemory()) {

            MigrationContext context = new MigrationContext(mem, tempMemSize, virtualMem, rwMemory);
            path.of(configuration.getRoot());

            // check if all tables have been upgraded already
            path.concat(TableUtils.UPGRADE_FILE_NAME).$();
            final boolean existed = ff.exists(path);
            long upgradeFd = openFileRWOrFail(ff, path);
            LOG.debug()
                    .$("open [fd=").$(upgradeFd)
                    .$(", path=").$(path)
                    .$(']').$();
            if (existed) {
                long readLen = ff.read(upgradeFd, mem, Integer.BYTES, 0);
                if (readLen == Integer.BYTES && Unsafe.getUnsafe().getInt(mem) >= latestVersion) {
                    LOG.info().$("table structures are up to date").$();
                    ff.close(upgradeFd);
                    upgradeFd = -1;
                }
            }

            if (upgradeFd != -1) {
                try {
                    LOG.info().$("upgrading database [version=").$(latestVersion).I$();
                    if (upgradeTables(context, latestVersion)) {
                        Unsafe.getUnsafe().putInt(mem, latestVersion);
                        long writeLen = ff.write(upgradeFd, mem, Integer.BYTES, 0);
                        if (writeLen < Integer.BYTES) {
                            LOG.error().$("could not write to ").$(UPGRADE_FILE_NAME)
                                    .$(" [fd=").$(upgradeFd).$(",errno=").$(ff.errno()).I$();
                        }
                    }
                } finally {
                    ff.close(upgradeFd);
                }
            }
        } finally {
            Unsafe.free(mem, tempMemSize);
        }
    }

    static MigrationAction getMigrationToVersion(int version) {
        return MIGRATIONS.getQuick(version - MIGRATIONS_LIST_OFFSET);
    }

    private static int getMigrationToVersionCriticality(int version) {
        return MIGRATIONS_CRITICALITY.getQuick(version - MIGRATIONS_LIST_OFFSET);
    }

    private static void setByVersion(int version, MigrationAction action, int criticality) {
        MIGRATIONS.setQuick(version - MIGRATIONS_LIST_OFFSET, action);
        MIGRATIONS_CRITICALITY.extendAndSet(version - MIGRATIONS_LIST_OFFSET, criticality);
    }

    private boolean upgradeTables(MigrationContext context, int latestVersion) {
        final FilesFacade ff = configuration.getFilesFacade();
        long mem = context.getTempMemory(8);
        updateSuccess = true;

        try (Path path = new Path(); Path copyPath = new Path()) {
            path.of(configuration.getRoot());
            copyPath.of(configuration.getRoot());
            final int rootLen = path.length();

            final NativeLPSZ nativeLPSZ = new NativeLPSZ();
            ff.iterateDir(path.$(), (name, type) -> {
                if (type == Files.DT_DIR) {
                    nativeLPSZ.of(name);
                    if (Chars.notDots(nativeLPSZ)) {
                        path.trimTo(rootLen);
                        path.concat(nativeLPSZ);
                        copyPath.trimTo(rootLen);
                        copyPath.concat(nativeLPSZ);
                        final int plen = path.length();
                        path.concat(TableUtils.META_FILE_NAME);

                        if (ff.exists(path.$())) {
                            final long fd = openFileRWOrFail(ff, path);
                            try {
                                if (ff.read(fd, mem, Integer.BYTES, META_OFFSET_VERSION) == Integer.BYTES) {
                                    int currentTableVersion = Unsafe.getUnsafe().getInt(mem);

                                    if (currentTableVersion < latestVersion) {
                                        LOG.info().$("upgrading [path=").$(path).$(",fromVersion=").$(currentTableVersion)
                                                .$(",toVersion=").$(latestVersion).I$();

                                        copyPath.trimTo(plen);
                                        backupFile(ff, path, copyPath, TableUtils.META_FILE_NAME, currentTableVersion);

                                        path.trimTo(plen);
                                        context.of(path, copyPath, fd);

                                        for (int i = currentTableVersion + 1; i <= latestVersion; i++) {
                                            MigrationAction migration = getMigrationToVersion(i);
                                            try {
                                                if (migration != null) {
                                                    LOG.info().$("upgrading table [path=").$(path).$(",toVersion=").$(i).I$();
                                                    migration.migrate(context);
                                                }
                                            } catch (Exception e) {
                                                LOG.error().$("failed to upgrade table path=")
                                                        .$(path.trimTo(plen))
                                                        .$(", exception: ")
                                                        .$(e).$();

                                                if (getMigrationToVersionCriticality(i) != 0) {
                                                    throw e;
                                                }
                                                updateSuccess = false;
                                                return;
                                            }

                                            Unsafe.getUnsafe().putInt(mem, i);
                                            if (ff.write(fd, mem, Integer.BYTES, META_OFFSET_VERSION) != Integer.BYTES) {
                                                // Table is migrated but we cannot write new version
                                                // to meta file
                                                // This is critical, table potentially left in unusable state
                                                throw CairoException.instance(ff.errno())
                                                        .put("failed to write updated version to table Metadata file [path=")
                                                        .put(path.trimTo(plen))
                                                        .put(",latestVersion=")
                                                        .put(i)
                                                        .put(']');
                                            }
                                        }
                                    }
                                    return;
                                }
                                updateSuccess = false;
                                throw CairoException.instance(ff.errno()).put("Could not update table [path=").put(path).put(']');
                            } finally {
                                ff.close(fd);
                                path.trimTo(plen);
                                copyPath.trimTo(plen);
                            }
                        }
                    }
                }
            });
            LOG.info().$("upgraded tables to ").$(latestVersion).$();
        }
        return updateSuccess;
    }

    private static void backupFile(FilesFacade ff, Path src, Path toTemp, String backupName, int version) {
        // make a copy
        int copyPathLen = toTemp.length();
        try {
            toTemp.concat(backupName).put(".v").put(version);
            for (int i = 1; ff.exists(toTemp.$()); i++) {
                // if backup file already exists
                // add .<num> at the end until file name is unique
                LOG.info().$("back up file exists, [path=").$(toTemp).I$();
                toTemp.trimTo(copyPathLen);
                toTemp.concat(backupName).put(".v").put(version).put(".").put(i);
            }

            LOG.info().$("back up coping file [from=").$(src).$(",to=").$(toTemp).I$();
            if (ff.copy(src.$(), toTemp.$()) < 0) {
                throw CairoException.instance(ff.errno()).put("Cannot backup transaction file [to=").put(toTemp).put(']');
            }
        } finally {
            toTemp.trimTo(copyPathLen);
        }
    }

    @FunctionalInterface
    interface MigrationAction {
        void migrate(MigrationContext context);
    }

    static int readIntAtOffset(FilesFacade ff, Path path, long tempMem4b, long fd) {
        if (ff.read(fd, tempMem4b, Integer.BYTES, EngineMigration.TX_STRUCT_UPDATE_1_META_OFFSET_PARTITION_BY) != Integer.BYTES) {
            throw CairoException.instance(ff.errno()).put("Cannot read: ").put(path);
        }
        return Unsafe.getUnsafe().getInt(tempMem4b);
    }

    private static class MigrationActions {
        public static void addTblMetaHysteresis(MigrationContext migrationContext) {
            Path path = migrationContext.getTablePath();
            final FilesFacade ff = migrationContext.getFf();
            path.concat(META_FILE_NAME).$();
            if (!ff.exists(path)) {
                LOG.error().$("meta file does not exist, nothing to migrate [path=").$(path).I$();
                return;
            }
            // Metadata file should already be backed up
            long tempMem = migrationContext.getTempMemory(8);
            Unsafe.getUnsafe().putInt(tempMem, migrationContext.getConfiguration().getO3MaxUncommittedRows());
            if (ff.write(migrationContext.metadataFd, tempMem, Integer.BYTES, META_OFFSET_O3_MAX_UNCOMMITTED_ROWS) != Integer.BYTES) {
                throw CairoException.instance(ff.errno()).put("Cannot update metadata [path=").put(path).put(']');
            }

            Unsafe.getUnsafe().putLong(tempMem, migrationContext.getConfiguration().getO3CommitHysteresisInMicros());
            if (ff.write(migrationContext.metadataFd, tempMem, Long.BYTES, META_OFFSET_O3_COMMIT_HYSTERESIS_IN_MICROS) != Long.BYTES) {
                throw CairoException.instance(ff.errno()).put("Cannot update metadata [path=").put(path).put(']');
            }
        }

        private static void assignTableId(MigrationContext migrationContext) {
            long mem = migrationContext.getTempMemory(8);
            FilesFacade ff = migrationContext.getFf();
            Path path = migrationContext.getTablePath();

            LOG.info().$("setting table id in [path=").$(path).I$();
            Unsafe.getUnsafe().putInt(mem, migrationContext.getNextTableId());
            if (ff.write(migrationContext.getMetadataFd(), mem, Integer.BYTES, META_OFFSET_TABLE_ID) == Integer.BYTES) {
                return;
            }
            throw CairoException.instance(ff.errno()).put("Could not update table id [path=").put(path).put(']');
        }

        private static void rebuildTransactionFile(MigrationContext migrationContext) {
            // Update transaction file
            // Before there was 1 int per symbol and list of removed partitions
            // Now there is 2 ints per symbol and 4 longs per each non-removed partition

            Path path = migrationContext.getTablePath();
            final FilesFacade ff = migrationContext.getFf();
            int pathDirLen = path.length();

            path.concat(TXN_FILE_NAME).$();
            if (!ff.exists(path)) {
                LOG.error().$("tx file does not exist, nothing to migrate [path=").$(path).I$();
                return;
            }
            backupFile(ff, path, migrationContext.getTablePath2(), TXN_FILE_NAME, VERSION_TX_STRUCT_UPDATE_1 - 1);

            LOG.debug().$("opening for rw [path=").$(path).I$();
            MappedReadWriteMemory txMem = migrationContext.createRwMemoryOf(ff, path.$(), ff.getPageSize());
            long tempMem8b = migrationContext.getTempMemory(8);

            PagedVirtualMemory txFileUpdate = migrationContext.getTempVirtualMem();
            txFileUpdate.clear();
            txFileUpdate.jumpTo(0);

            try {
                int symbolsCount = txMem.getInt(TX_STRUCT_UPDATE_1_OFFSET_MAP_WRITER_COUNT);
                for (int i = 0; i < symbolsCount; i++) {
                    long symbolCountOffset = TX_STRUCT_UPDATE_1_OFFSET_MAP_WRITER_COUNT + (i + 1L) * Integer.BYTES;
                    int symDistinctCount = txMem.getInt(symbolCountOffset);
                    txFileUpdate.putInt(symDistinctCount);
                    txFileUpdate.putInt(symDistinctCount);
                }

                // Set partition segment size as 0 for now
                long partitionSegmentOffset = txFileUpdate.getAppendOffset();
                txFileUpdate.putInt(0);

                int partitionBy = readIntAtOffset(ff, path, tempMem8b, migrationContext.getMetadataFd());
                if (partitionBy != PartitionBy.NONE) {
                    path.trimTo(pathDirLen);
                    writeAttachedPartitions(ff, tempMem8b, path, txMem, partitionBy, symbolsCount, txFileUpdate);
                }
                long updateSize = txFileUpdate.getAppendOffset();
                long partitionSegmentSize = updateSize - partitionSegmentOffset - Integer.BYTES;
                txFileUpdate.putInt(partitionSegmentOffset, (int) partitionSegmentSize);

                // Save txFileUpdate to tx file starting at LOCAL_TX_OFFSET_MAP_WRITER_COUNT + 4
                long writeOffset = TX_STRUCT_UPDATE_1_OFFSET_MAP_WRITER_COUNT + Integer.BYTES;
                txMem.jumpTo(writeOffset);

                for (int i = 0, size = txFileUpdate.getPageCount(); i < size && updateSize > 0; i++) {
                    long writeSize = Math.min(updateSize, txFileUpdate.getPageSize(i));
                    txMem.putBlockOfBytes(txFileUpdate.getPageAddress(i), writeSize);
                    updateSize -= writeSize;
                }

                assert updateSize == 0;
            } finally {
                txMem.close();
            }
        }

        private static void writeAttachedPartitions(
                FilesFacade ff,
                long tempMem8b,
                Path path,
                MappedReadWriteMemory txMem,
                int partitionBy,
                int symbolsCount,
                PagedVirtualMemory writeTo
        ) {
            int rootLen = path.length();

            long minTimestamp = txMem.getLong(TX_OFFSET_MIN_TIMESTAMP);
            long maxTimestamp = txMem.getLong(TX_OFFSET_MAX_TIMESTAMP);
            long transientCount = txMem.getLong(TX_OFFSET_TRANSIENT_ROW_COUNT);

            Timestamps.TimestampFloorMethod timestampFloorMethod = getPartitionFloor(partitionBy);
            Timestamps.TimestampAddMethod timestampAddMethod = getPartitionAdd(partitionBy);

            final long tsLimit = timestampFloorMethod.floor(maxTimestamp);
            for (long ts = timestampFloorMethod.floor(minTimestamp); ts < tsLimit; ts = timestampAddMethod.calculate(ts, 1)) {
                path.trimTo(rootLen);
                setPathForPartition(path, partitionBy, ts, false);
                if (ff.exists(path.concat(TX_STRUCT_UPDATE_1_ARCHIVE_FILE_NAME).$())) {
                    if (!removedPartitionsIncludes(ts, txMem, symbolsCount)) {
                        long partitionSize = TableUtils.readLongAtOffset(ff, path, tempMem8b, 0);

                        // Update tx file with 4 longs per partition
                        writeTo.putLong(ts);
                        writeTo.putLong(partitionSize);
                        writeTo.putLong(-1L);
                        writeTo.putLong(0L);
                    }
                }
            }
            // last partition
            writeTo.putLong(tsLimit);
            writeTo.putLong(transientCount);
            writeTo.putLong(-1);
            writeTo.putLong(0);
        }

        private static boolean removedPartitionsIncludes(long ts, ReadWriteVirtualMemory txMem, int symbolsCount) {
            long removedPartitionLo = TX_STRUCT_UPDATE_1_OFFSET_MAP_WRITER_COUNT + (symbolsCount + 1L) * Integer.BYTES;
            long removedPartitionCount = txMem.getInt(removedPartitionLo);
            long removedPartitionsHi = removedPartitionLo + Long.BYTES * removedPartitionCount;

            for (long offset = removedPartitionLo + Integer.BYTES; offset < removedPartitionsHi; offset += Long.BYTES) {
                long removedPartition = txMem.getLong(offset);
                if (removedPartition == ts) {
                    return true;
                }
            }
            return false;
        }
    }

    class MigrationContext {
        private final long tempMemory;
        private final int tempMemoryLen;
        private final PagedVirtualMemory tempVirtualMem;
        private final MappedReadWriteMemory rwMemory;
        private Path tablePath;
        private long metadataFd;
        private Path tablePath2;

        public MigrationContext(long mem, int tempMemSize, PagedVirtualMemory tempVirtualMem, MappedReadWriteMemory rwMemory) {
            this.tempMemory = mem;
            this.tempMemoryLen = tempMemSize;
            this.tempVirtualMem = tempVirtualMem;
            this.rwMemory = rwMemory;
        }

        public CairoConfiguration getConfiguration() {
            return configuration;
        }

        public FilesFacade getFf() {
            return configuration.getFilesFacade();
        }

        public long getMetadataFd() {
            return metadataFd;
        }

        public int getNextTableId() {
            return (int) engine.getNextTableId();
        }

        public MappedReadWriteMemory createRwMemoryOf(FilesFacade ff, Path path, long pageSize) {
            // re-use same rwMemory
            // assumption that it is re-usable after the close() and then of()  methods called.
            rwMemory.of(ff, path, pageSize);
            return rwMemory;
        }

        public Path getTablePath() {
            return tablePath;
        }

        public Path getTablePath2() {
            return tablePath2;
        }

        public long getTempMemory(int size) {
            if (size <= tempMemoryLen) {
                return tempMemory;
            }
            throw new UnsupportedOperationException("No temp memory of size "
                    + size
                    + " is allocate. Only "
                    + tempMemoryLen
                    + " is available");
        }

        public PagedVirtualMemory getTempVirtualMem() {
            return tempVirtualMem;
        }

        public MigrationContext of(Path path, Path pathCopy, long metadataFd) {
            this.tablePath = path;
            this.tablePath2 = pathCopy;
            this.metadataFd = metadataFd;
            return this;
        }
    }

    static {
        MIGRATIONS.extendAndSet(ColumnType.VERSION - MIGRATIONS_LIST_OFFSET, null);
        setByVersion(VERSION_THAT_ADDED_TABLE_ID, MigrationActions::assignTableId, 1);
        setByVersion(VERSION_TX_STRUCT_UPDATE_1, MigrationActions::rebuildTransactionFile, 0);
        setByVersion(VERSION_TBL_META_HYSTERESIS, MigrationActions::addTblMetaHysteresis, 0);
    }
}
