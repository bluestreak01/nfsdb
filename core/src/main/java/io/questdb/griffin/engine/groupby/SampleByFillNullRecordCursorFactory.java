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

package io.questdb.griffin.engine.groupby;

import io.questdb.cairo.*;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapFactory;
import io.questdb.cairo.map.MapKey;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.SqlExecutionInterruptor;
import io.questdb.griffin.engine.EmptyTableNoSizeRecordCursor;
import io.questdb.griffin.engine.functions.GroupByFunction;
import io.questdb.griffin.engine.functions.constants.*;
import io.questdb.std.*;
import org.jetbrains.annotations.NotNull;

public class SampleByFillNullRecordCursorFactory extends AbstractSampleByRecordCursorFactory {

    protected final Map map;
    private final AbstractNoRecordSampleByCursor cursor;
    private final ObjList<GroupByFunction> groupByFunctions;
    private final RecordSink mapSink;

    public SampleByFillNullRecordCursorFactory(
            CairoConfiguration configuration,
            RecordCursorFactory base,
            @NotNull TimestampSampler timestampSampler,
            @Transient @NotNull ListColumnFilter listColumnFilter,
            @Transient @NotNull BytecodeAssembler asm,
            @Transient @NotNull ArrayColumnTypes keyTypes,
            @Transient @NotNull ArrayColumnTypes valueTypes,
            RecordMetadata groupByMetadata,
            ObjList<GroupByFunction> groupByFunctions,
            ObjList<Function> recordFunctions,
            @Transient @NotNull IntList recordFunctionPositions,
            int timestampIndex,
            Function timezoneNameFunc,
            Function offsetFunc
    ) throws SqlException {

        super(base, groupByMetadata, recordFunctions, timezoneNameFunc, offsetFunc);
        this.groupByFunctions = groupByFunctions;
        // sink will be storing record columns to map key
        this.mapSink = RecordSinkFactory.getInstance(asm, base.getMetadata(), listColumnFilter, false);
        // this is the map itself, which we must not forget to free when factory closes
        this.map = MapFactory.createMap(configuration, keyTypes, valueTypes);
        try {
            this.cursor = new SampleByFillValueRecordCursor(
                    map,
                    mapSink,
                    groupByFunctions,
                    recordFunctions,
                    createPlaceholderFunctions(recordFunctions, recordFunctionPositions),
                    timestampIndex,
                    timestampSampler
            );
        } catch (Throwable e) {
            Misc.freeObjList(recordFunctions);
            Misc.free(map);
            throw e;
        }
    }

    @NotNull
    public static ObjList<Function> createPlaceholderFunctions(
            ObjList<Function> recordFunctions,
            IntList recordFunctionPositions
    ) throws SqlException {
        final ObjList<Function> placeholderFunctions = new ObjList<>();
        for (int i = 0, n = recordFunctions.size(); i < n; i++) {
            Function function = recordFunctions.getQuick(i);
            if (function instanceof GroupByFunction) {
                switch (function.getType()) {
                    case ColumnType.INT:
                        placeholderFunctions.add(IntConstant.NULL);
                        break;
                    case ColumnType.LONG:
                        placeholderFunctions.add(LongConstant.NULL);
                        break;
                    case ColumnType.FLOAT:
                        placeholderFunctions.add(FloatConstant.NULL);
                        break;
                    case ColumnType.DOUBLE:
                        placeholderFunctions.add(DoubleConstant.NULL);
                        break;
                    case ColumnType.BYTE:
                        placeholderFunctions.add(ByteConstant.ZERO);
                        break;
                    case ColumnType.SHORT:
                        placeholderFunctions.add(ShortConstant.ZERO);
                        break;
                    default:
                        throw SqlException.$(recordFunctionPositions.getQuick(i), "Unsupported type: ").put(ColumnType.nameOf(function.getType()));
                }
            } else {
                placeholderFunctions.add(function);
            }
        }
        return placeholderFunctions;
    }

    @Override
    public void close() {
        super.close();
        Misc.free(map);
    }

    @Override
    public AbstractNoRecordSampleByCursor getRawCursor() {
        return cursor;
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) {
        final RecordCursor baseCursor = base.getCursor(executionContext);
        final SqlExecutionInterruptor interruptor = executionContext.getSqlExecutionInterruptor();
        try {
            map.clear();

            // This factory fills gaps in data. To do that we
            // have to know all possible key values. Essentially, every time
            // we sample we return same set of key values with different
            // aggregation results and timestamp

            int n = groupByFunctions.size();
            final Record baseCursorRecord = baseCursor.getRecord();
            while (baseCursor.hasNext()) {
                interruptor.checkInterrupted();
                MapKey key = map.withKey();
                mapSink.copy(baseCursorRecord, key);
                MapValue value = key.createValue();
                if (value.isNew()) {
                    // timestamp is always stored in value field 0
                    value.putLong(0, Numbers.LONG_NaN);
                    // have functions reset their columns to "zero" state
                    // this would set values for when keys are not found right away
                    for (int i = 0; i < n; i++) {
                        groupByFunctions.getQuick(i).setNull(value);
                    }
                }
            }

            // empty map? this means that base cursor was empty
            if (map.size() == 0) {
                baseCursor.close();
                return EmptyTableNoSizeRecordCursor.INSTANCE;
            }

            // because we pass base cursor twice we have to go back to top
            // for the second run
            baseCursor.toTop();
            boolean next = baseCursor.hasNext();
            // we know base cursor has value
            assert next;
            return initFunctionsAndCursor(executionContext, baseCursor);
        } catch (Throwable ex) {
            baseCursor.close();
            throw ex;
        }
    }
}
