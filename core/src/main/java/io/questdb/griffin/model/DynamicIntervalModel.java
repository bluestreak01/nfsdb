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

package io.questdb.griffin.model;

import io.questdb.cairo.sql.Function;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.std.LongList;
import io.questdb.std.Mutable;
import io.questdb.std.Numbers;
import io.questdb.std.ObjList;
import io.questdb.std.datetime.microtime.Timestamps;

public class DynamicIntervalModel implements IntervalModel, Mutable {
    private final ObjList<RuntimePeriodIntrinsic> runtimePeriods = new ObjList<>();
    private final Interval tempInterval = new Interval();
    private StaticIntervalsModel staticIntervalsModel = new StaticIntervalsModel();

    @Override
    public void clear() {
        staticIntervalsModel.clear();
        runtimePeriods.clear();
    }

    @Override
    public void clearInterval() {
        clear();
    }

    @Override
    public boolean hasIntervals() {
        return isDynamic() || staticIntervalsModel.intervals != null;
    }

    @Override
    public void intersectEmpty() {
        runtimePeriods.clear();
        staticIntervalsModel.intersectEmpty();
    }

    @Override
    public void intersectIntervals(long lo, long hi) {
        if (isDynamic()) {
            runtimePeriods.add(getNextRuntimePeriodIntrinsic().setInterval(IntervalOperation.INTERSECT, lo, hi));
        } else {
            staticIntervalsModel.intersectIntervals(lo, hi);
        }
    }

    @Override
    public void intersectIntervals(CharSequence seq, int lo, int lim, int position) throws SqlException {
        if (isDynamic()) {
            IntervalUtils.parseIntervalEx(seq, lo, lim, position, tempInterval);
            runtimePeriods.add(getNextRuntimePeriodIntrinsic().setInterval(IntervalOperation.INTERSECT, tempInterval));
        } else {
            staticIntervalsModel.intersectIntervals(seq, lo, lim, position);
        }
    }

    @Override
    public void subtractIntervals(long lo, long hi) {
        if (isDynamic()) {
            runtimePeriods.add(getNextRuntimePeriodIntrinsic().setInterval(IntervalOperation.SUBTRACT, lo, hi));
        } else {
            staticIntervalsModel.subtractIntervals(lo, hi);
        }
    }

    @Override
    public void subtractIntervals(CharSequence seq, int lo, int lim, int position) throws SqlException {
        if (isDynamic()) {
            IntervalUtils.parseIntervalEx(seq, lo, lim, position, tempInterval);
            runtimePeriods.add(getNextRuntimePeriodIntrinsic().setInterval(IntervalOperation.SUBTRACT, tempInterval));
        } else {
            staticIntervalsModel.subtractIntervals(seq, lo, lim, position);
        }
    }

    public boolean isEmptySet() {
        return !isDynamic() && staticIntervalsModel.isEmptySet();
    }

    public RuntimeIntrinsicIntervalModel getIntervalModel() {
        if (!isDynamic()) {
            LongList intervalCopy = staticIntervalsModel.intervals != null ? new LongList(staticIntervalsModel.intervals) : null;
            return new StaticRuntimeIntrinsicIntervalModel(intervalCopy);
        } else {
            return new DynamicRuntimeIntrinsicIntervalModel(staticIntervalsModel.intervals, runtimePeriods);
        }
    }

    public void intersectIntervals(long low, Function function, long funcAdjust) {
        // Intersect nothing with anything is still nothing.
        if (!isDynamic() && staticIntervalsModel.isEmptySet()) return;
        runtimePeriods.add(getNextRuntimePeriodIntrinsic().setLess(IntervalOperation.INTERSECT, low, function, funcAdjust));
    }

    public void intersectIntervals(Function function, long hi, long funcAdjust) {
        // Intersect nothing with anything is still nothing.
        if (!isDynamic() && staticIntervalsModel.isEmptySet()) return;
        runtimePeriods.add(getNextRuntimePeriodIntrinsic().setGreater(IntervalOperation.INTERSECT, function, hi, funcAdjust));
    }

    public void intersectEquals(Function lo) {
        // Intersect nothing with anything is still nothing.
        if (!isDynamic() && staticIntervalsModel.isEmptySet()) return;
        runtimePeriods.add(getNextRuntimePeriodIntrinsic().setEquals(IntervalOperation.INTERSECT_EQUALS, lo));
    }

    private RuntimePeriodIntrinsic getNextRuntimePeriodIntrinsic() {
        // We cannot pool it here, objects will be transferred to cursor factory.
        return new RuntimePeriodIntrinsic();
    }

    private boolean isDynamic() {
        return runtimePeriods.size() > 0;
    }

    private static class DynamicRuntimeIntrinsicIntervalModel implements RuntimeIntrinsicIntervalModel {
        private final LongList intervals;
        private static final LongList emptyIntervals = new LongList();
        private final StaticIntervalsModel tempModel = new StaticIntervalsModel();
        private final ObjList<RuntimePeriodIntrinsic> runtimePeriods;

        private DynamicRuntimeIntrinsicIntervalModel(LongList intervals, ObjList<RuntimePeriodIntrinsic> runtimePeriods) {
            this.intervals = intervals;
            this.runtimePeriods = new ObjList<>(runtimePeriods.size());
            this.runtimePeriods.addAll(runtimePeriods);
        }

        @Override
        public LongList calculateIntervals(SqlExecutionContext sqlContext) {
            tempModel.of(intervals);
            for (int i = 0; i < this.runtimePeriods.size(); i++) {
                RuntimePeriodIntrinsic toApply = runtimePeriods.getQuick(i);
                long lo, hi;
                if (toApply.dynamicLo != null){
                    toApply.dynamicLo.init(null, sqlContext);
                    lo = toApply.dynamicLo.getTimestamp(null);
                    // Numbers.LONG_NaN == Long.MIN_VALUE
                    // there is no way to understand if the function evaluated to min value or
                    // NULL. Assume it's null and it's period starting with undefined boundary.
                    if (lo == Numbers.LONG_NaN) {
                        return empty();
                    }
                    lo += toApply.dynamicIncrement;
                } else {
                    lo = toApply.staticLo;
                }

                if (toApply.dynamicHi != null){
                    toApply.dynamicHi.init(null, sqlContext);
                    hi = toApply.dynamicHi.getTimestamp(null);
                    if (hi == Numbers.LONG_NaN) {
                        return empty();
                    }
                    hi += toApply.dynamicIncrement;
                } else {
                    hi = toApply.staticHi;
                }


                switch (toApply.getOperation()) {
                    case IntervalOperation.SUBTRACT:
                        tempModel.applySubtract(
                                lo,
                                hi,
                                toApply.period,
                                toApply.periodType,
                                toApply.count);
                        break;

                    case IntervalOperation.INTERSECT:
                        tempModel.applyIntersect(
                                lo,
                                hi,
                                toApply.period,
                                toApply.periodType,
                                toApply.count);
                        break;

                    case IntervalOperation.INTERSECT_EQUALS:
                        // Single value stored in lo
                        tempModel.applyIntersect(
                                lo,
                                lo,
                                toApply.period,
                                toApply.periodType,
                                toApply.count);
                        break;

                    default:
                }
            }

            return copy(tempModel.intervals);
        }

        private LongList copy(LongList intervals) {
            // We have to copy so that different query executions based on the same plan
            // have independent periods list
            return intervals == null ? null : new LongList(intervals);
        }

        private LongList empty() {
            return emptyIntervals;
        }

        @Override
        public boolean isFocused(Timestamps.TimestampFloorMethod floorDd) {
            return false;
        }
    }
}
