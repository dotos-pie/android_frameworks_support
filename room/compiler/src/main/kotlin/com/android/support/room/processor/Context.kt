/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.support.room.processor

import com.android.support.room.log.RLog
import com.android.support.room.preconditions.Checks
import com.android.support.room.processor.cache.Cache
import com.android.support.room.solver.TypeAdapterStore
import com.android.support.room.verifier.DatabaseVerifier
import java.io.File
import java.util.LinkedHashSet
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.type.TypeMirror

class Context private constructor(val processingEnv: ProcessingEnvironment,
                                  val logger: RLog,
                                  val typeConverters: CustomConverterProcessor.ProcessResult,
                                  val cache : Cache) {
    val checker: Checks = Checks(logger)
    val COMMON_TYPES: Context.CommonTypes = Context.CommonTypes(processingEnv)

    val typeAdapterStore by lazy { TypeAdapterStore(this, typeConverters.converters) }

    // set when database and its entities are processed.
    var databaseVerifier: DatabaseVerifier? = null

    companion object {
        val ARG_OPTIONS by lazy {
            ProcessorOptions.values().map { it.argName }
        }
    }

    constructor(processingEnv: ProcessingEnvironment) : this(processingEnv,
            RLog(RLog.ProcessingEnvMessager(processingEnv), emptySet(), null),
            CustomConverterProcessor.ProcessResult.EMPTY,
            Cache(null, LinkedHashSet(), emptySet())) {
    }

    class CommonTypes(val processingEnv: ProcessingEnvironment) {
        val STRING: TypeMirror by lazy {
            processingEnv.elementUtils.getTypeElement("java.lang.String").asType()
        }
    }

    val schemaOutFolder by lazy {
        val arg = processingEnv.options[ProcessorOptions.OPTION_SCHEMA_FOLDER.argName]
        if (arg?.isNotEmpty() ?: false) {
            File(arg)
        } else {
            null
        }
    }

    fun <T> collectLogs(handler: (Context) -> T): Pair<T, RLog.CollectingMessager> {
        val collector = RLog.CollectingMessager()
        val subContext = Context(processingEnv,
                RLog(collector, logger.suppressedWarnings, logger.defaultElement),
                this.typeConverters, cache)
        subContext.databaseVerifier = databaseVerifier
        val result = handler(subContext)
        return Pair(result, collector)
    }

    fun fork(element: Element): Context {
        val suppressedWarnings = SuppressWarningProcessor.getSuppressedWarnings(element)
        val processConvertersResult = CustomConverterProcessor.findConverters(this, element)
        // order here is important since the sub context should give priority to new converters.
        val subTypeConverters = processConvertersResult + this.typeConverters
        val subSuppressedWarnings = suppressedWarnings + logger.suppressedWarnings
        val subCache = Cache(cache, subTypeConverters.classes, subSuppressedWarnings)
        val subContext = Context(processingEnv,
                RLog(logger.messager, subSuppressedWarnings, element), subTypeConverters, subCache)
        subContext.databaseVerifier = databaseVerifier
        return subContext
    }

    enum class ProcessorOptions(val argName: String) {
        OPTION_SCHEMA_FOLDER("room.schemaLocation")
    }
}
