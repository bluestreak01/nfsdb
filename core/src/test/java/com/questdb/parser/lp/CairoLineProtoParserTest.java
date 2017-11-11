package com.questdb.parser.lp;

import com.questdb.cairo.*;
import com.questdb.cairo.pool.WriterPool;
import com.questdb.ex.NumericException;
import com.questdb.misc.Chars;
import com.questdb.misc.Files;
import com.questdb.misc.FilesFacade;
import com.questdb.std.clock.Clock;
import com.questdb.std.str.CompositePath;
import com.questdb.std.str.LPSZ;
import com.questdb.std.time.DateFormatUtils;
import com.questdb.store.factory.configuration.JournalStructure;
import com.questdb.test.tools.TestMilliClock;
import com.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

public class CairoLineProtoParserTest extends AbstractCairoTest {

    private static final CairoConfiguration configuration = new DefaultCairoConfiguration(root);
    private final LineProtoLexer lexer = new LineProtoLexer(4096);

    @Test
    public void testAddColumn() throws Exception {
        final String expected = "tag\ttag2\tfield\tf4\tfield2\tfx\ttimestamp\tf5\n" +
                "abc\txyz\t10000\t9.034000000000\tstr\ttrue\t1970-01-01T00:01:40.000Z\tNaN\n" +
                "woopsie\tdaisy\t2000\t3.088910000000\tcomment\ttrue\t1970-01-01T00:01:40.000Z\tNaN\n" +
                "444\td555\t510\t1.400000000000\tcomment\ttrue\t1970-01-01T00:01:40.000Z\t55\n" +
                "666\t777\t410\t1.100000000000\tcomment X\tfalse\t1970-01-01T00:01:40.000Z\tNaN\n";

        final String lines = "tab,tag=abc,tag2=xyz field=10000i,f4=9.034,field2=\"str\",fx=true 100000\n" +
                "tab,tag=woopsie,tag2=daisy field=2000i,f4=3.08891,field2=\"comment\",fx=true 100000\n" +
                "tab,tag=444,tag2=d555 field=510i,f4=1.4,f5=55i,field2=\"comment\",fx=true 100000\n" +
                "tab,tag=666,tag2=777 field=410i,f4=1.1,field2=\"comment\\ X\",fx=false 100000\n";


        assertThat(expected, lines, "tab");
    }

    @Test
    public void testAppendExistingTable() throws Exception {
        final String expected = "double\tint\tbool\tsym1\tsym2\tstr\ttimestamp\n" +
                "1.600000000000\t15\ttrue\t\txyz\tstring1\t2017-10-03T10:00:00.000Z\n" +
                "1.300000000000\t11\tfalse\tabc\t\tstring2\t2017-10-03T10:00:00.010Z\n";


        JournalStructure struct = new JournalStructure("x").$double("double").$int("int").$bool("bool").$sym("sym1").$sym("sym2").$str("str").$ts();
        CairoTestUtils.createTable(configuration.getFilesFacade(), root, struct);

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n";

        CairoConfiguration configuration = new DefaultCairoConfiguration(root) {
            @Override
            public Clock getClock() {
                try {
                    return new TestMilliClock(DateFormatUtils.parseDateTime("2017-10-03T10:00:00.000Z"), 10);
                } catch (NumericException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        assertThat(expected, lines, "x", configuration);
    }

    @Test
    public void testBadDouble1() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "xyz\t1.600000000000\t15\ttrue\tstring1\t1970-01-01T00:25:00.000Z\t\n" +
                "\t9.400000000000\t6\tfalse\tstring3\t1970-01-01T00:25:00.000Z\trow3\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t1970-01-01T00:25:00.000Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t1970-01-01T00:28:20.000Z\n" +
                "66\tbox\t7.900000000000\t1970-01-01T00:28:20.000Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\" 1500000\n" +
                "x,sym1=abc double=1x.3,int=11i,bool=false,str=\"string2\" 1500000\n" + // <-- error here
                "y,asym1=55,asym2=box adouble=5.9 1700000\n" +
                "x,sym1=row3 double=9.4,int=6i,bool=false,str=\"string3\" 1500000\n" +
                "y,asym1=66,asym2=box adouble=7.9 1700000\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\" 1500000\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testBadDouble2() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "\t1.300000000000\t11\tfalse\tstring2\t1970-01-01T00:25:00.000Z\tabc\n" +
                "\t9.400000000000\t6\tfalse\tstring3\t1970-01-01T00:25:00.000Z\trow3\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t1970-01-01T00:25:00.000Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t1970-01-01T00:28:20.000Z\n" +
                "66\tbox\t7.900000000000\t1970-01-01T00:28:20.000Z\n";

        String lines = "x,sym2=xyz double=1.6x,int=15i,bool=true,str=\"string1\" 1500000\n" +  // <-- error here
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\" 1500000\n" +
                "y,asym1=55,asym2=box adouble=5.9 1700000\n" +
                "x,sym1=row3 double=9.4,int=6i,bool=false,str=\"string3\" 1500000\n" +
                "y,asym1=66,asym2=box adouble=7.9 1700000\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\" 1500000\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testBadInt1() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "xyz\t1.600000000000\t15\ttrue\tstring1\t1970-01-01T00:25:00.000Z\t\n" +
                "\t9.400000000000\t6\tfalse\tstring3\t1970-01-01T00:25:00.000Z\trow3\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t1970-01-01T00:25:00.000Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t1970-01-01T00:28:20.000Z\n" +
                "66\tbox\t7.900000000000\t1970-01-01T00:28:20.000Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\" 1500000\n" +
                "x,sym1=abc double=1.3,int=1s1i,bool=false,str=\"string2\" 1500000\n" + // <-- error here
                "y,asym1=55,asym2=box adouble=5.9 1700000\n" +
                "x,sym1=row3 double=9.4,int=6i,bool=false,str=\"string3\" 1500000\n" +
                "y,asym1=66,asym2=box adouble=7.9 1700000\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\" 1500000\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testBadInt2() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "\t1.300000000000\t11\tfalse\tstring2\t1970-01-01T00:25:00.000Z\tabc\n" +
                "\t9.400000000000\t6\tfalse\tstring3\t1970-01-01T00:25:00.000Z\trow3\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t1970-01-01T00:25:00.000Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t1970-01-01T00:28:20.000Z\n" +
                "66\tbox\t7.900000000000\t1970-01-01T00:28:20.000Z\n";

        String lines = "x,sym2=xyz double=1.6,int=1i5i,bool=true,str=\"string1\" 1500000\n" +  // <-- error here
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\" 1500000\n" +
                "y,asym1=55,asym2=box adouble=5.9 1700000\n" +
                "x,sym1=row3 double=9.4,int=6i,bool=false,str=\"string3\" 1500000\n" +
                "y,asym1=66,asym2=box adouble=7.9 1700000\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\" 1500000\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testBadTimestamp1() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "\t1.300000000000\t11\tfalse\tstring2\t1970-01-01T00:25:00.000Z\tabc\n" +
                "\t9.400000000000\t6\tfalse\tstring3\t1970-01-01T00:25:00.000Z\trow3\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t1970-01-01T00:25:00.000Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t1970-01-01T00:28:20.000Z\n" +
                "66\tbox\t7.900000000000\t1970-01-01T00:28:20.000Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\" 1234ab\n" + // <-- error here
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\" 1500000\n" +
                "y,asym1=55,asym2=box adouble=5.9 1700000\n" +
                "x,sym1=row3 double=9.4,int=6i,bool=false,str=\"string3\" 1500000\n" +
                "y,asym1=66,asym2=box adouble=7.9 1700000\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\" 1500000\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testBadTimestamp2() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "xyz\t1.600000000000\t15\ttrue\tstring1\t1970-01-01T00:00:01.234Z\t\n" +
                "\t1.300000000000\t11\tfalse\tstring2\t1970-01-01T00:25:00.000Z\tabc\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t1970-01-01T00:25:00.000Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t1970-01-01T00:28:20.000Z\n" +
                "66\tbox\t7.900000000000\t1970-01-01T00:28:20.000Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\" 1234\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\" 1500000\n" +
                "y,asym1=55,asym2=box adouble=5.9 1700000\n" +
                "x,sym1=row3 double=9.4,int=6i,bool=false,str=\"string3\" 1500x000\n" + // <-- error here
                "y,asym1=66,asym2=box adouble=7.9 1700000\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\" 1500000\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testBusyTable() throws Exception {
        final String expected = "double\tint\tbool\tsym1\tsym2\tstr\ttimestamp\n";


        JournalStructure struct = new JournalStructure("x").$double("double").$int("int").$bool("bool").$sym("sym1").$sym("sym2").$str("str").$ts();
        CairoTestUtils.createTable(configuration.getFilesFacade(), root, struct);

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n";

        CairoConfiguration configuration = new DefaultCairoConfiguration(root) {
            @Override
            public Clock getClock() {
                try {
                    return new TestMilliClock(DateFormatUtils.parseDateTime("2017-10-03T10:00:00.000Z"), 10);
                } catch (NumericException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        // open writer so that pool cannot have it
        try (TableWriter ignored = new TableWriter(configuration.getFilesFacade(), root, struct.getName())) {
            assertThat(expected, lines, "x", configuration);
        }
    }

    @Test
    public void testCannotCreateTable() throws Exception {
        TestFilesFacade ff = new TestFilesFacade() {
            boolean called = false;

            @Override
            public int mkdirs(LPSZ path, int mode) {
                if (Chars.endsWith(path, "x" + Files.SEPARATOR)) {
                    called = true;
                    return -1;
                }
                return super.mkdirs(path, mode);
            }

            @Override
            public boolean wasCalled() {
                return called;
            }
        };

        final String expected = "sym\tdouble\tint\tbool\tstr\ttimestamp\n" +
                "zzz\t1.300000000000\t11\tfalse\tnice\t2017-10-03T10:00:00.000Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n" +
                "y,sym=zzz double=1.3,int=11i,bool=false,str=\"nice\"\n";

        CairoConfiguration configuration = new DefaultCairoConfiguration(root) {
            @Override
            public FilesFacade getFilesFacade() {
                return ff;
            }

            @Override
            public Clock getClock() {
                try {
                    return new TestMilliClock(DateFormatUtils.parseDateTime("2017-10-03T10:00:00.000Z"), 10);
                } catch (NumericException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        assertThat(expected, lines, "y", configuration);


        Assert.assertTrue(ff.wasCalled());

        try (CompositePath path = new CompositePath()) {
            Assert.assertEquals(TableUtils.TABLE_DOES_NOT_EXIST, TableUtils.exists(ff, path, root, "all"));
        }
    }

    @Test
    public void testCreateAndAppend() throws Exception {
        final String expected = "tag\ttag2\tfield\tf4\tfield2\tfx\ttimestamp\n" +
                "abc\txyz\t10000\t9.034000000000\tstr\ttrue\t1970-01-01T00:01:40.000Z\n" +
                "woopsie\tdaisy\t2000\t3.088910000000\tcomment\ttrue\t1970-01-01T00:01:40.000Z\n";

        final String lines = "tab,tag=abc,tag2=xyz field=10000i,f4=9.034,field2=\"str\",fx=true 100000\n" +
                "tab,tag=woopsie,tag2=daisy field=2000i,f4=3.08891,field2=\"comment\",fx=true 100000\n";


        assertThat(expected, lines, "tab");
    }

    @Test
    public void testCreateAndAppendTwoTables() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "xyz\t1.600000000000\t15\ttrue\tstring1\t2017-10-03T10:00:00.000Z\t\n" +
                "\t1.300000000000\t11\tfalse\tstring2\t2017-10-03T10:00:00.010Z\tabc\n" +
                "\t0.900000000000\t6\tfalse\tstring3\t2017-10-03T10:00:00.030Z\trow3\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t2017-10-03T10:00:00.050Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t2017-10-03T10:00:00.020Z\n" +
                "66\tbox\t7.900000000000\t2017-10-03T10:00:00.040Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n" +
                "y,asym1=55,asym2=box adouble=5.9\n" +
                "x,sym1=row3 double=.9,int=6i,bool=false,str=\"string3\"\n" +
                "y,asym1=66,asym2=box adouble=7.9\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\"\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testCreateTable() throws Exception {
        final String expected = "tag\ttag2\tfield\tf4\tfield2\tfx\ttimestamp\n" +
                "abc\txyz\t10000\t9.034000000000\tstr\ttrue\t1970-01-01T00:01:40.000Z\n";
        final String lines = "measurement,tag=abc,tag2=xyz field=10000i,f4=9.034,field2=\"str\",fx=true 100000\n";
        assertThat(expected, lines, "measurement");
    }

    @Test
    public void testReservedName() throws Exception {
        final String expected = "sym\tdouble\tint\tbool\tstr\ttimestamp\n" +
                "ok\t2.100000000000\t11\tfalse\tdone\t2017-10-03T10:00:00.000Z\n";


        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n" +
                "y,sym=ok double=2.1,int=11i,bool=false,str=\"done\"\n";

        CairoConfiguration configuration = new DefaultCairoConfiguration(root) {
            @Override
            public Clock getClock() {
                try {
                    return new TestMilliClock(DateFormatUtils.parseDateTime("2017-10-03T10:00:00.000Z"), 10);
                } catch (NumericException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        try (CompositePath path = new CompositePath()) {
            Files.mkdirs(path.of(root).concat("x").put(Files.SEPARATOR).$(), configuration.getMkDirMode());
            assertThat(expected, lines, "y", configuration);
            Assert.assertEquals(TableUtils.TABLE_RESERVED, TableUtils.exists(configuration.getFilesFacade(), path, root, "x"));
        }
    }

    @Test
    public void testSyntaxError() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "xyz\t1.600000000000\t15\ttrue\tstring1\t2017-10-03T10:00:00.000Z\t\n" +
                "\t1.300000000000\t11\tfalse\tstring2\t2017-10-03T10:00:00.010Z\tabc\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t2017-10-03T10:00:00.040Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t2017-10-03T10:00:00.020Z\n" +
                "66\tbox\t7.900000000000\t2017-10-03T10:00:00.030Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n" +
                "y,asym1=55,asym2=box adouble=5.9\n" +
                "x,sym1=row3 double=,int=6i,bool=false,str=\"string3\"\n" +
                "y,asym1=66,asym2=box adouble=7.9\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\"\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testTypeMismatch1() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "xyz\t1.600000000000\t15\ttrue\tstring1\t2017-10-03T10:00:00.000Z\t\n" +
                "\t1.300000000000\t11\tfalse\tstring2\t2017-10-03T10:00:00.010Z\tabc\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t2017-10-03T10:00:00.040Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t2017-10-03T10:00:00.020Z\n" +
                "66\tbox\t7.900000000000\t2017-10-03T10:00:00.030Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n" +
                "y,asym1=55,asym2=box adouble=5.9\n" +
                "x,sym1=row3 double=\"z\",int=6i,bool=false,str=\"string3\"\n" +
                "y,asym1=66,asym2=box adouble=7.9\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\"\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testTypeMismatch2() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "xyz\t1.600000000000\t15\ttrue\tstring1\t2017-10-03T10:00:00.000Z\t\n" +
                "\t1.300000000000\t11\tfalse\tstring2\t2017-10-03T10:00:00.010Z\tabc\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t2017-10-03T10:00:00.040Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t2017-10-03T10:00:00.020Z\n" +
                "66\tbox\t7.900000000000\t2017-10-03T10:00:00.030Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n" +
                "y,asym1=55,asym2=box adouble=5.9\n" +
                "x,sym1=row3 double=9.4,int=6.3,bool=false,str=\"string3\"\n" +
                "y,asym1=66,asym2=box adouble=7.9\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\"\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testUnquotedString1() throws Exception {
        final String expected1 = "sym2\tdouble\tint\tbool\tstr\ttimestamp\tsym1\n" +
                "xyz\t1.600000000000\t15\ttrue\tstring1\t2017-10-03T10:00:00.000Z\t\n" +
                "\t9.400000000000\t6\tfalse\tstring3\t2017-10-03T10:00:00.020Z\trow3\n" +
                "\t0.300000000000\t91\ttrue\tstring4\t2017-10-03T10:00:00.040Z\trow4\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t2017-10-03T10:00:00.010Z\n" +
                "66\tbox\t7.900000000000\t2017-10-03T10:00:00.030Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=\"string1\"\n" +
                "x,sym1=abc double=1.3,int=11i,bool=false,str=string2\"\n" + // <-- error here
                "y,asym1=55,asym2=box adouble=5.9\n" +
                "x,sym1=row3 double=9.4,int=6i,bool=false,str=\"string3\"\n" +
                "y,asym1=66,asym2=box adouble=7.9\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\"\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    @Test
    public void testUnquotedString2() throws Exception {
        final String expected1 = "sym1\tdouble\tint\tbool\tstr\ttimestamp\n" +
                "abc\t1.300000000000\t11\tfalse\tstring2\t2017-10-03T10:00:00.000Z\n" +
                "row3\t9.400000000000\t6\tfalse\tstring3\t2017-10-03T10:00:00.020Z\n" +
                "row4\t0.300000000000\t91\ttrue\tstring4\t2017-10-03T10:00:00.040Z\n";

        final String expected2 = "asym1\tasym2\tadouble\ttimestamp\n" +
                "55\tbox\t5.900000000000\t2017-10-03T10:00:00.010Z\n" +
                "66\tbox\t7.900000000000\t2017-10-03T10:00:00.030Z\n";

        String lines = "x,sym2=xyz double=1.6,int=15i,bool=true,str=string1\"\n" + // <-- error here
                "x,sym1=abc double=1.3,int=11i,bool=false,str=\"string2\"\n" +
                "y,asym1=55,asym2=box adouble=5.9\n" +
                "x,sym1=row3 double=9.4,int=6i,bool=false,str=\"string3\"\n" +
                "y,asym1=66,asym2=box adouble=7.9\n" +
                "x,sym1=row4 double=.3,int=91i,bool=true,str=\"string4\"\n";

        assertMultiiTable(expected1, expected2, lines);
    }

    private void assertMultiiTable(String expected1, String expected2, String lines) throws Exception {
        CairoConfiguration configuration = new DefaultCairoConfiguration(root) {
            @Override
            public Clock getClock() {
                try {
                    return new TestMilliClock(DateFormatUtils.parseDateTime("2017-10-03T10:00:00.000Z"), 10);
                } catch (NumericException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        assertThat(expected1, lines, "x", configuration);
        assertTable(expected2, "y");
    }

    private void assertTable(CharSequence expected, CharSequence tableName) throws IOException {
        try (TableReader reader = new TableReader(configuration.getFilesFacade(), root, tableName)) {
            assertThat(expected, reader, reader.getMetadata(), true);
        }
    }

    private void assertThat(String expected, String lines, CharSequence tableName, CairoConfiguration configuration) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (WriterPool pool = new WriterPool(configuration)) {
                try (CairoLineProtoParser parser = new CairoLineProtoParser(configuration, pool)) {
                    lexer.withParser(parser);
                    lexer.parse(new ByteArrayByteSequence(lines.getBytes("UTF8")));
                    lexer.parseLast();
                    parser.commitAll();
                }
            }
            assertTable(expected, tableName);
        });
    }

    private void assertThat(String expected, String lines, CharSequence tableName) throws Exception {
        assertThat(expected, lines, tableName, configuration);
    }
}