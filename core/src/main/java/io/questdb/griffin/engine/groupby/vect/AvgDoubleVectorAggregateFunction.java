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

package io.questdb.griffin.engine.groupby.vect;

import io.questdb.cairo.sql.Record;
import io.questdb.griffin.engine.functions.DoubleFunction;
import io.questdb.std.Vect;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

public class AvgDoubleVectorAggregateFunction extends DoubleFunction implements VectorAggregateFunction {

    private final DoubleAdder doubleAdder = new DoubleAdder();
    private final LongAdder longAdder = new LongAdder();
    private final int columnIndex;

    public AvgDoubleVectorAggregateFunction(int position, int columnIndex) {
        super(position);
        this.columnIndex = columnIndex;
    }

    @Override
    public void aggregate(long address, long count) {
        double avg = Vect.avgDouble(address, count);
        doubleAdder.add(avg);
        longAdder.add(1);
    }

    @Override
    public int getColumnIndex() {
        return columnIndex;
    }

    @Override
    public void clear() {
        doubleAdder.reset();
        longAdder.reset();
    }

    @Override
    public double getDouble(Record rec) {
        double sum = doubleAdder.sum();
        long count = longAdder.sum();
        return sum / count;
    }
}
