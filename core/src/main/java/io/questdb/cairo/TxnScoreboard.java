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

import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.Files;
import io.questdb.std.Os;
import io.questdb.std.Unsafe;
import io.questdb.std.str.Path;

import static io.questdb.cairo.TableUtils.TXN_FILE_NAME;

public class TxnScoreboard {

    public static final long READER_NOT_YET_ACTIVE = -1;
    private static final Log LOG = LogFactory.getLog(TxnScoreboard.class);

    public static boolean acquire(long pTxnScoreboard, long txn) {
        assert pTxnScoreboard > 0;
        LOG.debug().$("acquire [p=").$(pTxnScoreboard).$(", txn=").$(txn).$(']').$();
        return acquire0(pTxnScoreboard, txn);
    }

    public static void close(long pTxnScoreboard) {
        if (pTxnScoreboard != 0) {
            long x;
            LOG.debug().$("closing [p=").$(pTxnScoreboard).$(']').$();
            if ((x = close0(pTxnScoreboard)) == 0) {
                LOG.debug().$("closed [p=").$(pTxnScoreboard).$(']').$();
                Unsafe.recordMemAlloc(-getScoreboardSize());
            } else {
                LOG.debug().$("close called [p=").$(pTxnScoreboard).$(", remaining=").$(x)
                        .$(']').$();
            }
        }
    }

    public static long create(
            Path shmPath,
            long databaseIdLo,
            long databaseIdHi,
            CharSequence root,
            CharSequence tableName,
            int tableId
    ) {
        setShmName(shmPath, databaseIdLo, databaseIdHi, root, tableName, tableId);
        Unsafe.recordMemAlloc(getScoreboardSize());
        final long p = create0(shmPath.address(), tableId);
        if (p != 0) {
            LOG.info()
                    .$("open [table=").$(tableName)
                    .$(", p=").$(p)
                    .$(", shm=").$(shmPath)
                    .$(']').$();
            return p;
        }
        throw CairoException.instance(Os.errno()).put("could not open scoreboard [table=").put(tableName)
                .put(", shm=").put(shmPath)
                .put(']');
    }

    public static long newRef(long pTxnScoreboard) {
        if (pTxnScoreboard > 0) {
            LOG.debug().$("new ref [p=").$(pTxnScoreboard).$(']').$();
            return newRef0(pTxnScoreboard);
        }
        return pTxnScoreboard;
    }

    public static void release(long pTxnScoreboard, long txn) {
        assert pTxnScoreboard > 0;
        LOG.debug().$("release  [p=").$(pTxnScoreboard).$(", txn=").$(txn).$(']').$();
        release0(pTxnScoreboard, txn);
    }

    private static void setShmName(
            Path shmPath,
            long databaseIdLo,
            long databaseIdHi,
            CharSequence root,
            CharSequence tableName,
            int tableId
    ) {
        if (Os.type == Os.WINDOWS) {
            shmPath.of("Local\\");
            shmPath.put(databaseIdLo).put('-').put(databaseIdHi).put('-').put(tableName).$();
        } else if (Os.type != Os.OSX) {
            shmPath.of("/");
            shmPath.put(databaseIdLo).put('-').put(databaseIdHi).put('-').put(tableId).$();
        } else {
            // for OSX it's name of _txn file of the table
            shmPath.of(root).concat(tableName).concat(TXN_FILE_NAME).$();
        }
    }

    private native static boolean acquire0(long pTxnScoreboard, long txn);

    private native static long release0(long pTxnScoreboard, long txn);

    private native static long newRef0(long pTxnScoreboard);

    private static native long create0(long lpszName, long tableId);

    static native long getCount(long pTxnScoreboard, long txn);

    static native long init(long pTxnScoreboard, long txn);

    static native long getMin(long pTxnScoreboard);

    private static native long close0(long pTxnScoreboard);

    private static native long getScoreboardSize();

    static boolean isTxnUnused(long nameTxn, long readerTxn, long txnScoreboard) {
        return
                // readers had last partition open but they are inactive
                // (e.g. they are guaranteed to reload when they go active
                (readerTxn == nameTxn && getCount(txnScoreboard, readerTxn) == 0)
                        // there are no readers at all
                        || readerTxn == READER_NOT_YET_ACTIVE
                        // reader has more recent data in their view
                        || readerTxn > nameTxn;
    }
}
