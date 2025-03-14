/*
 * CBOMkit-action
 * Copyright (C) 2024 PQCA
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pqca.indexing;

import jakarta.annotation.Nonnull;
import java.io.File;
import java.util.Arrays;

public final class PythonIndexService extends IndexingService {

    public PythonIndexService(@Nonnull File baseDirectory) {
        super(baseDirectory, "python", ".py");
    }

    @Override
    boolean isModule(@Nonnull File[] files) {
        return Arrays.stream(files)
                .anyMatch(
                        f ->
                                f.getName().equals("pyproject.toml")
                                        || f.getName().equals("setup.cfg")
                                        || f.getName().equals("setup.py"));
    }

    @Override
    boolean excludeFromIndexing(@Nonnull File file) {
        return file.getPath().contains("tests/");
    }
}
