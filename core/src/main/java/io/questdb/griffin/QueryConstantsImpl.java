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

package io.questdb.griffin;

import io.questdb.std.Mutable;
import io.questdb.std.Numbers;
import io.questdb.std.datetime.microtime.MicrosecondClock;

import java.util.concurrent.atomic.AtomicLong;

public class QueryConstantsImpl implements QueryConstants, Mutable {
    private final static AtomicLong QUERY_ID_SEQUENCE = new AtomicLong();
    private long nowTimestamp = Numbers.LONG_NaN;
    private long queryId;
    private MicrosecondClock clock;

    public QueryConstantsImpl(MicrosecondClock clock) {
        this.clock = clock;
    }

    @Override
    public void clear() {
        queryId = -1;
        nowTimestamp = Numbers.LONG_NaN;
    }

    @Override
    public long getNowTimestamp() {
        if (nowTimestamp == Numbers.LONG_NaN)
            return nowTimestamp = clock.getTicks();

        return nowTimestamp;
    }

    @Override
    public long getQueryId() {
        return queryId == 0 ? (queryId = QUERY_ID_SEQUENCE.incrementAndGet()) : queryId;
    }
}
