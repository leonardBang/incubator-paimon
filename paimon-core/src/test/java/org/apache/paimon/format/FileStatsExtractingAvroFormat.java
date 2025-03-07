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

package org.apache.paimon.format;

import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.stats.TestFileStatsExtractor;
import org.apache.paimon.types.RowType;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;

/** An avro {@link FileFormat} for test. It provides a {@link FileStatsExtractor}. */
public class FileStatsExtractingAvroFormat extends FileFormat {

    private final FileFormat avro;

    public FileStatsExtractingAvroFormat() {
        super("avro");
        avro = FileFormat.fromIdentifier("avro", new Options());
    }

    @Override
    public FormatReaderFactory createReaderFactory(
            RowType type, int[][] projection, @Nullable List<Predicate> filters) {
        return avro.createReaderFactory(type, projection, filters);
    }

    @Override
    public FormatWriterFactory createWriterFactory(RowType type) {
        return avro.createWriterFactory(type);
    }

    @Override
    public Optional<FileStatsExtractor> createStatsExtractor(RowType type) {
        return Optional.of(new TestFileStatsExtractor(this, type));
    }
}
