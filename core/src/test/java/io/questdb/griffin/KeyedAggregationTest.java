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

import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.griffin.engine.functions.rnd.SharedRandom;
import io.questdb.std.Rnd;
import io.questdb.test.tools.TestUtils;
import org.junit.Before;
import org.junit.Test;

public class KeyedAggregationTest extends AbstractGriffinTest {
    @Before
    public void setUp3() {
        SharedRandom.RANDOM.set(new Rnd());
    }

    @Test
    public void testIntSymbolResolution() throws Exception {
        assertQuery(
                "s2\tsum\n" +
                        "\t104119.880948161\n" +
                        "a1\t103804.62242300605\n" +
                        "a2\t104433.68659571148\n" +
                        "a3\t104341.28852517322\n",
                "select s2, sum(val) from tab order by s2",
                "create table tab as (select rnd_symbol('s1','s2','s3', null) s1, rnd_symbol('a1','a2','a3', null) s2, rnd_double(2) val from long_sequence(1000000))",
                null, true
        );
    }

    @Test
    public void testIntSymbolAddKeyMidTable() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tab as (select rnd_symbol('s1','s2','s3', null) s1, rnd_double(2) val from long_sequence(1000000))", sqlExecutionContext);
            compiler.compile("alter table tab add column s2 symbol cache", sqlExecutionContext);
            compiler.compile("insert into tab select rnd_symbol('s1','s2','s3', null), rnd_double(2), rnd_symbol('a1','a2','a3', null) s2 from long_sequence(1000000)", sqlExecutionContext);

            try (
                    RecordCursorFactory factory = compiler.compile("select s2, sum(val) from tab order by s2", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {

                String expected = "s2\tsum\n" +
                        "\t520447.6629968713\n" +
                        "a1\t104308.65839619507\n" +
                        "a2\t104559.2867475151\n" +
                        "a3\t104044.11326997809\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }
        });
    }

    @Test
    public void testIntSymbolAddValueMidTable() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tab as (select rnd_symbol('s1','s2','s3', null) s1 from long_sequence(1000000))", sqlExecutionContext);
            compiler.compile("alter table tab add column val double", sqlExecutionContext);
            compiler.compile("insert into tab select rnd_symbol('a1','a2','a3', null), rnd_double(2) from long_sequence(1000000)", sqlExecutionContext);

            try (
                    RecordCursorFactory factory = compiler.compile("select s1, sum(val) from tab order by s1", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {

                String expected = "s1\tsum\n" +
                        "\t104083.77969067449\n" +
                        "a1\t103982.62399952614\n" +
                        "a2\t104702.89752880299\n" +
                        "a3\t104299.02298329721\n" +
                        "s1\tNaN\n" +
                        "s2\tNaN\n" +
                        "s3\tNaN\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }
        });
    }

    @Test
    public void testIntSymbolAddBothMidTable() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tab as (select rnd_symbol('s1','s2','s3', null) s1 from long_sequence(1000000))", sqlExecutionContext);
            compiler.compile("alter table tab add column s2 symbol cache", sqlExecutionContext);
            compiler.compile("alter table tab add column val double", sqlExecutionContext);
            compiler.compile("insert into tab select null, rnd_symbol('a1','a2','a3', null), rnd_double(2) from long_sequence(1000000)", sqlExecutionContext);

            try (
                    RecordCursorFactory factory = compiler.compile("select s2, sum(val) from tab order by s2", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {

                String expected = "s2\tsum\n" +
                        "\t104083.77969067449\n" +
                        "a1\t103982.62399952614\n" +
                        "a2\t104702.89752880299\n" +
                        "a3\t104299.02298329721\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }
        });
    }

    @Test
    public void testIntSymbolSumTimeRange() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tab as (select rnd_symbol('s1','s2','s3', null) s1, rnd_double(2) val, timestamp_sequence(0, 1000000) t from long_sequence(1000000)) timestamp(t) partition by DAY", sqlExecutionContext);
            try (
                    RecordCursorFactory factory = compiler.compile("select s1, sum(val) from tab where t > '1970-01-04T12:00' and t < '1970-01-07T11:00' order by s1", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {
                String expected = "s1\tsum\n" +
                        "\t26636.385784265905\n" +
                        "s1\t26427.49917110396\n" +
                        "s2\t26891.053965922987\n" +
                        "s3\t26459.102633238483\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }
        });
    }

    @Test
    public void testIntSymbolSumAddKeyTimeRange() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tab as (select rnd_symbol('s1','s2','s3', null) s1, rnd_double(2) val, timestamp_sequence(0, 1000000) t from long_sequence(1000000)) timestamp(t) partition by DAY", sqlExecutionContext);
            compiler.compile("alter table tab add column s2 symbol cache", sqlExecutionContext);
            compiler.compile("insert into tab select rnd_symbol('s1','s2','s3', null), rnd_double(2), timestamp_sequence(cast('1970-01-13T00:00:00.000000Z' as timestamp), 1000000), rnd_symbol('a1','a2','a3', null) s2 from long_sequence(1000000)", sqlExecutionContext);

            // test with key falling within null columns
            try (
                    RecordCursorFactory factory = compiler.compile("select s2, sum(val) from tab where t > '1970-01-04T12:00' and t < '1970-01-07T11:00' order by s2", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {
                String expected = "s2\tsum\n" +
                        "\t106414.04155453121\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }

            /// test key on overlap
            try (
                    RecordCursorFactory factory = compiler.compile("select s2, sum(val) from tab where t > '1970-01-12T12:00' and t < '1970-01-14T11:00' order by s2", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {
                String expected = "s2\tsum\n" +
                        "\t15636.977658744854\n" +
                        "a1\t13073.816187889399\n" +
                        "a2\t13240.269899560482\n" +
                        "a3\t13223.021189180576\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }
        });
    }

    @Test
    public void testIntSymbolSumAddValueTimeRange() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tab as (select rnd_symbol('s1','s2','s3', null) s1, timestamp_sequence(0, 1000000) t from long_sequence(1000000)) timestamp(t) partition by DAY", sqlExecutionContext);
            compiler.compile("alter table tab add column val double ", sqlExecutionContext);
            compiler.compile("insert into tab select rnd_symbol('s1','s2','s3', null), timestamp_sequence(cast('1970-01-13T00:00:00.000000Z' as timestamp), 1000000), rnd_double(2) from long_sequence(1000000)", sqlExecutionContext);

            // test with key falling within null columns
            try (
                    RecordCursorFactory factory = compiler.compile("select s1, sum(val) from tab where t > '1970-01-04T12:00' and t < '1970-01-07T11:00' order by s1", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {
                String expected = "s1\tsum\n" +
                        "\tNaN\n" +
                        "s1\tNaN\n" +
                        "s2\tNaN\n" +
                        "s3\tNaN\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }

            /// test key on overlap
            try (
                    RecordCursorFactory factory = compiler.compile("select s1, sum(val) from tab where t > '1970-01-12T12:00' and t < '1970-01-14T11:00' order by s1", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {
                String expected = "s1\tsum\n" +
                        "\t13168.088431585857\n" +
                        "s1\t12972.778275274499\n" +
                        "s2\t13388.118328291552\n" +
                        "s3\t12929.34474745085\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }
        });
    }

    @Test
    public void testIntSymbolSumAddKeyPartitioned() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tab as (select rnd_symbol('s1','s2','s3', null) s1, rnd_double(2) val, timestamp_sequence(0, 1000000) t from long_sequence(1000000)) timestamp(t) partition by DAY", sqlExecutionContext);
            compiler.compile("alter table tab add column s2 symbol cache", sqlExecutionContext);
            compiler.compile("insert into tab select rnd_symbol('s1','s2','s3', null), rnd_double(2), timestamp_sequence(cast('1970-01-13T00:00:00.000000Z' as timestamp), 1000000), rnd_symbol('a1','a2','a3', null) s2 from long_sequence(1000000)", sqlExecutionContext);

            // test with key falling within null columns
            try (
                    RecordCursorFactory factory = compiler.compile("select s2, sum(val) from tab order by s2", sqlExecutionContext).getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(sqlExecutionContext)
            ) {
                String expected = "s2\tsum\n" +
                        "\t520447.6629968692\n" +
                        "a1\t104308.65839619662\n" +
                        "a2\t104559.28674751727\n" +
                        "a3\t104044.11326997768\n";

                sink.clear();
                printer.print(cursor, factory.getMetadata(), true);
                TestUtils.assertEquals(expected, sink);
            }
        });
    }
}
