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

import io.questdb.cairo.CairoSecurityContext;
import io.questdb.cairo.CairoWorkScheduler;
import io.questdb.griffin.engine.functions.bind.BindVariableService;
import org.jetbrains.annotations.Nullable;

public class SqlExecutionContextImpl implements SqlExecutionContext {
    private BindVariableService bindVariableService;
    private CairoSecurityContext cairoSecurityContext;
    @Nullable
    private CairoWorkScheduler workScheduler;

    @Override
    public BindVariableService getBindVariableService() {
        return bindVariableService;
    }

    @Override
    public CairoSecurityContext getCairoSecurityContext() {
        return cairoSecurityContext;
    }

    public SqlExecutionContextImpl with(
            CairoSecurityContext cairoSecurityContext,
            BindVariableService bindVariableService,
            @Nullable CairoWorkScheduler workScheduler
    ) {
        this.cairoSecurityContext = cairoSecurityContext;
        this.bindVariableService = bindVariableService;
        this.workScheduler = workScheduler;
        return this;
    }

    @Override
    @Nullable
    public CairoWorkScheduler getWorkScheduler() {
        return workScheduler;
    }
}
