/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.source;

import org.apache.paimon.KeyValue;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.utils.RecordWriter;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsAddition;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsChange;
import org.apache.flink.connector.file.src.util.RecordAndPosition;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.BigIntType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.RowKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.paimon.flink.LogicalTypeConversion.toDataType;
import static org.apache.paimon.flink.source.FileStoreSourceSplitSerializerTest.newFile;
import static org.apache.paimon.flink.source.FileStoreSourceSplitSerializerTest.newSourceSplit;
import static org.apache.paimon.mergetree.compact.MergeTreeCompactManagerTest.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test for {@link FileStoreSourceSplitReader}. */
public class FileStoreSourceSplitReaderTest {

    @TempDir java.nio.file.Path tempDir;

    @BeforeEach
    public void beforeEach() throws Exception {
        SchemaManager schemaManager =
                new SchemaManager(LocalFileIO.create(), new Path(tempDir.toUri()));
        schemaManager.createTable(
                new Schema(
                        toDataType(
                                        new RowType(
                                                Arrays.asList(
                                                        new RowType.RowField("k", new BigIntType()),
                                                        new RowType.RowField("v", new BigIntType()),
                                                        new RowType.RowField(
                                                                "default", new IntType()))))
                                .getFields(),
                        Collections.singletonList("default"),
                        Arrays.asList("k", "default"),
                        Collections.emptyMap(),
                        null));
    }

    @Test
    public void testPrimaryKey() throws Exception {
        innerTestOnce(false, 0);
    }

    @Test
    public void testValueCount() throws Exception {
        innerTestOnce(true, 0);
    }

    @Test
    public void testPrimaryKeySkip() throws Exception {
        innerTestOnce(false, 4);
    }

    @Test
    public void testValueCountSkip() throws Exception {
        innerTestOnce(true, 7);
    }

    private FileStoreSourceSplitReader<RecordAndPosition<RowData>> createReader(
            TableRead tableRead, @Nullable Long limit) {
        return new FileStoreSourceSplitReader<>(RecordsFunction.forIterate(), tableRead, limit);
    }

    private void innerTestOnce(boolean valueCountMode, int skip) throws Exception {
        TestChangelogDataReadWrite rw = new TestChangelogDataReadWrite(tempDir.toString());
        FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader =
                createReader(
                        valueCountMode ? rw.createReadWithValueCount() : rw.createReadWithKey(),
                        null);

        List<Tuple2<Long, Long>> input = kvs();
        List<DataFileMeta> files = rw.writeFiles(row(1), 0, input);

        assignSplit(reader, newSourceSplit("id1", row(1), 0, files, skip));

        RecordsWithSplitIds<RecordAndPosition<RowData>> records = reader.fetch();

        List<Tuple2<RowKind, Long>> expected;
        if (valueCountMode) {
            expected =
                    Arrays.asList(
                            new Tuple2<>(RowKind.INSERT, 1L),
                            new Tuple2<>(RowKind.INSERT, 2L),
                            new Tuple2<>(RowKind.INSERT, 2L),
                            new Tuple2<>(RowKind.INSERT, 3L),
                            new Tuple2<>(RowKind.INSERT, 3L),
                            new Tuple2<>(RowKind.DELETE, 4L),
                            new Tuple2<>(RowKind.INSERT, 5L),
                            new Tuple2<>(RowKind.DELETE, 6L),
                            new Tuple2<>(RowKind.DELETE, 6L));
        } else {
            expected =
                    input.stream()
                            .map(t -> new Tuple2<>(RowKind.INSERT, t.f1))
                            .collect(Collectors.toList());
        }

        List<Tuple2<RowKind, Long>> result = readRecords(records, "id1", skip);
        assertThat(result).isEqualTo(expected.subList(skip, expected.size()));

        records = reader.fetch();
        assertRecords(records, "id1", "id1", 0, null);

        reader.close();
    }

    @Test
    public void testPrimaryKeyWithDelete() throws Exception {
        TestChangelogDataReadWrite rw = new TestChangelogDataReadWrite(tempDir.toString());
        FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader =
                createReader(rw.createReadWithKey(), null);

        List<Tuple2<Long, Long>> input = kvs();
        RecordWriter<KeyValue> writer = rw.createMergeTreeWriter(row(1), 0);
        for (Tuple2<Long, Long> tuple2 : input) {
            writer.write(
                    new KeyValue()
                            .replace(
                                    GenericRow.of(tuple2.f0),
                                    org.apache.paimon.types.RowKind.INSERT,
                                    GenericRow.of(tuple2.f1)));
        }
        writer.write(
                new KeyValue()
                        .replace(
                                GenericRow.of(222L),
                                org.apache.paimon.types.RowKind.DELETE,
                                GenericRow.of(333L)));
        List<DataFileMeta> files = writer.prepareCommit(true).newFilesIncrement().newFiles();
        writer.close();

        assignSplit(reader, newSourceSplit("id1", row(1), 0, files, true));
        RecordsWithSplitIds<RecordAndPosition<RowData>> records = reader.fetch();

        List<Tuple2<RowKind, Long>> expected =
                input.stream()
                        .map(t -> new Tuple2<>(RowKind.INSERT, t.f1))
                        .collect(Collectors.toList());
        expected.add(new Tuple2<>(RowKind.DELETE, 333L));

        List<Tuple2<RowKind, Long>> result = readRecords(records, "id1", 0);
        assertThat(result).isEqualTo(expected);

        records = reader.fetch();
        assertRecords(records, "id1", "id1", 0, null);

        reader.close();
    }

    @Test
    public void testMultipleBatchInSplit() throws Exception {
        TestChangelogDataReadWrite rw = new TestChangelogDataReadWrite(tempDir.toString());
        FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader =
                createReader(rw.createReadWithKey(), null);

        List<Tuple2<Long, Long>> input1 = kvs();
        List<DataFileMeta> files = rw.writeFiles(row(1), 0, input1);

        List<Tuple2<Long, Long>> input2 = kvs(6);
        List<DataFileMeta> files2 = rw.writeFiles(row(1), 0, input2);
        files.addAll(files2);

        assignSplit(reader, newSourceSplit("id1", row(1), 0, files));

        RecordsWithSplitIds<RecordAndPosition<RowData>> records = reader.fetch();
        assertRecords(
                records,
                null,
                "id1",
                0,
                input1.stream().map(t -> t.f1).collect(Collectors.toList()));

        records = reader.fetch();
        assertRecords(
                records,
                null,
                "id1",
                6,
                input2.stream().map(t -> t.f1).collect(Collectors.toList()));

        records = reader.fetch();
        assertRecords(records, "id1", "id1", 0, null);

        reader.close();
    }

    @Test
    public void testRestore() throws Exception {
        TestChangelogDataReadWrite rw = new TestChangelogDataReadWrite(tempDir.toString());
        FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader =
                createReader(rw.createReadWithKey(), null);

        List<Tuple2<Long, Long>> input = kvs();
        List<DataFileMeta> files = rw.writeFiles(row(1), 0, input);

        assignSplit(reader, newSourceSplit("id1", row(1), 0, files, 3));

        RecordsWithSplitIds<RecordAndPosition<RowData>> records = reader.fetch();
        assertRecords(
                records,
                null,
                "id1",
                3,
                input.subList(3, input.size()).stream()
                        .map(t -> t.f1)
                        .collect(Collectors.toList()));

        records = reader.fetch();
        assertRecords(records, "id1", "id1", 0, null);

        reader.close();
    }

    @Test
    public void testRestoreMultipleBatchInSplit() throws Exception {
        TestChangelogDataReadWrite rw = new TestChangelogDataReadWrite(tempDir.toString());
        FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader =
                createReader(rw.createReadWithKey(), null);

        List<Tuple2<Long, Long>> input1 = kvs();
        List<DataFileMeta> files = rw.writeFiles(row(1), 0, input1);

        List<Tuple2<Long, Long>> input2 = kvs(6);
        List<DataFileMeta> files2 = rw.writeFiles(row(1), 0, input2);
        files.addAll(files2);

        assignSplit(reader, newSourceSplit("id1", row(1), 0, files, 7));

        RecordsWithSplitIds<RecordAndPosition<RowData>> records = reader.fetch();
        assertRecords(
                records,
                null,
                "id1",
                7,
                Stream.concat(input1.stream(), input2.stream())
                        .skip(7)
                        .map(t -> t.f1)
                        .collect(Collectors.toList()));

        records = reader.fetch();
        assertRecords(records, "id1", "id1", 0, null);

        reader.close();
    }

    @Test
    public void testMultipleSplits() throws Exception {
        TestChangelogDataReadWrite rw = new TestChangelogDataReadWrite(tempDir.toString());
        FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader =
                createReader(rw.createReadWithKey(), null);

        List<Tuple2<Long, Long>> input1 = kvs();
        List<DataFileMeta> files1 = rw.writeFiles(row(1), 0, input1);
        assignSplit(reader, newSourceSplit("id1", row(1), 0, files1));

        List<Tuple2<Long, Long>> input2 = kvs();
        List<DataFileMeta> files2 = rw.writeFiles(row(2), 1, input2);
        assignSplit(reader, newSourceSplit("id2", row(2), 1, files2));

        RecordsWithSplitIds<RecordAndPosition<RowData>> records = reader.fetch();
        assertRecords(
                records,
                null,
                "id1",
                0,
                input1.stream().map(t -> t.f1).collect(Collectors.toList()));

        records = reader.fetch();
        assertRecords(records, "id1", "id1", 0, null);

        records = reader.fetch();
        assertRecords(
                records,
                null,
                "id2",
                0,
                input2.stream().map(t -> t.f1).collect(Collectors.toList()));

        records = reader.fetch();
        assertRecords(records, "id2", "id2", 0, null);

        reader.close();
    }

    @Test
    public void testNoSplit() throws Exception {
        TestChangelogDataReadWrite rw = new TestChangelogDataReadWrite(tempDir.toString());
        FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader =
                createReader(rw.createReadWithKey(), null);
        assertThatThrownBy(reader::fetch).hasMessageContaining("no split remaining");
        reader.close();
    }

    @Test
    public void testLimit() throws Exception {
        TestChangelogDataReadWrite rw = new TestChangelogDataReadWrite(tempDir.toString());
        FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader =
                createReader(rw.createReadWithKey(), 2L);

        List<Tuple2<Long, Long>> input = kvs();
        List<DataFileMeta> files = rw.writeFiles(row(1), 0, input);

        assignSplit(reader, newSourceSplit("id1", row(1), 0, files, 0));

        RecordsWithSplitIds<RecordAndPosition<RowData>> records = reader.fetch();

        List<Tuple2<RowKind, Long>> expected =
                input.stream()
                        .map(t -> new Tuple2<>(RowKind.INSERT, t.f1))
                        .collect(Collectors.toList());

        List<Tuple2<RowKind, Long>> result = readRecords(records, "id1", 0);
        assertThat(result).isEqualTo(expected.subList(0, 2));

        records = reader.fetch();
        assertRecords(records, "id1", null, 0, Collections.emptyList());

        // test limit without opening reader
        // create a new fake new file, throw exception if open it
        assignSplit(
                reader, newSourceSplit("id2", row(1), 0, Collections.singletonList(newFile(0)), 0));
        records = reader.fetch();
        assertRecords(records, "id2", null, 0, null);

        reader.close();
    }

    private void assertRecords(
            RecordsWithSplitIds<RecordAndPosition<RowData>> records,
            String finishedSplit,
            String nextSplit,
            long startRecordSkipCount,
            List<Long> expected) {
        if (finishedSplit != null) {
            assertThat(records.finishedSplits()).isEqualTo(Collections.singleton(finishedSplit));
            return;
        }

        List<Tuple2<RowKind, Long>> result = readRecords(records, nextSplit, startRecordSkipCount);
        assertThat(result.stream().map(t -> t.f1).collect(Collectors.toList())).isEqualTo(expected);
    }

    private List<Tuple2<RowKind, Long>> readRecords(
            RecordsWithSplitIds<RecordAndPosition<RowData>> records,
            String nextSplit,
            long startRecordSkipCount) {
        assertThat(records.finishedSplits()).isEmpty();
        assertThat(records.nextSplit()).isEqualTo(nextSplit);
        List<Tuple2<RowKind, Long>> result = new ArrayList<>();
        RecordAndPosition<RowData> record;
        while ((record = records.nextRecordFromSplit()) != null) {
            result.add(
                    new Tuple2<>(record.getRecord().getRowKind(), record.getRecord().getLong(0)));
            assertThat(record.getRecordSkipCount()).isEqualTo(++startRecordSkipCount);
        }
        records.recycle();
        return result;
    }

    private List<Tuple2<Long, Long>> kvs() {
        return kvs(0);
    }

    private List<Tuple2<Long, Long>> kvs(long keyBase) {
        List<Tuple2<Long, Long>> kvs = new ArrayList<>();
        kvs.add(new Tuple2<>(keyBase + 1L, 1L));
        kvs.add(new Tuple2<>(keyBase + 2L, 2L));
        kvs.add(new Tuple2<>(keyBase + 3L, 2L));
        kvs.add(new Tuple2<>(keyBase + 4L, -1L));
        kvs.add(new Tuple2<>(keyBase + 5L, 1L));
        kvs.add(new Tuple2<>(keyBase + 6L, -2L));
        return kvs;
    }

    private void assignSplit(
            FileStoreSourceSplitReader<RecordAndPosition<RowData>> reader,
            FileStoreSourceSplit split) {
        SplitsChange<FileStoreSourceSplit> splitsChange =
                new SplitsAddition<>(Collections.singletonList(split));
        reader.handleSplitsChanges(splitsChange);
    }
}
