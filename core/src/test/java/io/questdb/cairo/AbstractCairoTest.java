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

import io.questdb.MessageBus;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.FilesFacade;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.std.datetime.microtime.MicrosecondClockImpl;
import io.questdb.std.str.StringSink;
import io.questdb.test.tools.TestUtils;
import org.jetbrains.annotations.Nullable;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

public class AbstractCairoTest {

    protected static final StringSink sink = new StringSink();
    protected static final RecordCursorPrinter printer = new RecordCursorPrinter();
    private final static Log LOG = LogFactory.getLog(AbstractCairoTest.class);
    @ClassRule
    public static TemporaryFolder temp = new TemporaryFolder();
    protected static CharSequence root;
    protected static CairoConfiguration configuration;
    protected static MessageBus messageBus;
    protected static long currentMicros = -1;
    protected final static MicrosecondClock testMicrosClock =
            () -> currentMicros >= 0 ? currentMicros : MicrosecondClockImpl.INSTANCE.getTicks();
    protected static CairoEngine engine;
    protected static String inputRoot = null;
    protected static FilesFacade ff;

    @BeforeClass
    public static void setUpStatic() {
        // it is necessary to initialise logger before tests start
        // logger doesn't relinquish memory until JVM stops
        // which causes memory leak detector to fail should logger be
        // created mid-test
        LOG.info().$("begin").$();
        try {
            root = temp.newFolder("dbRoot").getAbsolutePath();
        } catch (IOException e) {
            throw new ExceptionInInitializerError();
        }
        configuration = new DefaultCairoConfiguration(root) {
            @Override
            public FilesFacade getFilesFacade() {
                if (ff != null) {
                    return ff;
                }
                return super.getFilesFacade();
            }

            @Override
            public MicrosecondClock getMicrosecondClock() {
                return testMicrosClock;
            }

            @Override
            public CharSequence getInputRoot() {
                return inputRoot;
            }
        };
        engine = new CairoEngine(configuration);
        messageBus = engine.getMessageBus();
    }

    @AfterClass
    public static void tearDownStatic() {
        engine.close();
    }

    @Before
    public void setUp() {
        TestUtils.createTestPath(root);
    }

    @After
    public void tearDown() {
        engine.resetTableId();
        engine.clear();
        TestUtils.removeTestPath(root);
    }

    protected static void assertMemoryLeak(TestUtils.LeakProneCode code) throws Exception {
        assertMemoryLeak(null, code);
    }

    protected static void assertMemoryLeak(@Nullable FilesFacade ff, TestUtils.LeakProneCode code) throws Exception {
        final FilesFacade ff2 = ff;
        TestUtils.assertMemoryLeak(() -> {
            AbstractCairoTest.ff = ff2;
            try {
                code.run();
                engine.releaseInactive();
                Assert.assertEquals(0, engine.getBusyWriterCount());
                Assert.assertEquals(0, engine.getBusyReaderCount());
            } finally {
                engine.clear();
                AbstractCairoTest.ff = null;
            }
        });
    }

    protected void assertCursor(CharSequence expected, RecordCursor cursor, RecordMetadata metadata, boolean header) {
        TestUtils.assertCursor(expected, cursor, metadata, header, sink);
    }

    protected void assertCursorTwoPass(CharSequence expected, RecordCursor cursor, RecordMetadata metadata) {
        assertCursor(expected, cursor, metadata, true);
        cursor.toTop();
        assertCursor(expected, cursor, metadata, true);
    }
}
