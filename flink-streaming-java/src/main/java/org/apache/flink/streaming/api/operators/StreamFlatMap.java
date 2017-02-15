/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
@Internal
public class StreamFlatMap<IN, OUT>
		extends AbstractUdfStreamOperator<OUT, FlatMapFunction<IN, OUT>>
		implements OneInputStreamOperator<IN, OUT> {

	private static final long serialVersionUID = 1L;

	private static final long TERMINATION_TIMEOUT = 5000L;

	private int parallelism;
	private ExecutorService executorService;
	private List<Callable<Void>> tasks;

	private transient TimestampedCollector<OUT> collector;

    public StreamFlatMap(FlatMapFunction<IN, OUT> flatMapper, int parallelism) {
        super(flatMapper);

        Preconditions.checkArgument(parallelism >= 0 ? true : false, "Invalid parallelism!");

        this.parallelism = parallelism;
        tasks = new ArrayList<>();

        setChainingStrategy();
    }

	public StreamFlatMap(FlatMapFunction<IN, OUT> flatMapper) {
		this(flatMapper, 0);
	}

	@Override
	public void open() throws Exception {
		super.open();

		if(canBeParallelized())
		    createExecutorService();
	}

	@Override
	public void close() throws Exception {
        if(canBeParallelized()) {
            executorService.invokeAll(tasks);

            closeExecutor();
        }

        super.close();
	}

	@Override
	public void processElement(final StreamRecord<IN> element) throws Exception {
        if(canBeParallelized())
            tasks.add(new ProcessElementTask(userFunction, element, new TimestampedCollector<>(output)));
        else
            new ProcessElementTask(userFunction, element, new TimestampedCollector<>(output)).processElement();
    }

    private boolean canBeParallelized() {
        return parallelism > 0;
    }

	private void setChainingStrategy() {
		chainingStrategy = ChainingStrategy.ALWAYS;
	}

	private void createExecutorService() {
		executorService = Executors.newFixedThreadPool(parallelism > 0 ? parallelism : 1);
	}

	private void closeExecutor() {
		executorService.shutdown();

		try {
			if (!executorService.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.MILLISECONDS))
                executorService.shutdownNow();
		} catch (InterruptedException interrupted) {
			executorService.shutdownNow();
		}
	}

    private class ProcessElementTask<IN, OUT> implements Callable {
        private FlatMapFunction<IN, OUT> userFunction;
        private StreamRecord<IN> element;
        private TimestampedCollector<OUT> collector;

        public ProcessElementTask(FlatMapFunction<IN, OUT> userFunction,  StreamRecord<IN> element,
                                  TimestampedCollector<OUT> collector) {
            this.userFunction = userFunction;
            this.element = element;
            this.collector = collector;
        }

        @Override
        public Void call() throws Exception {
            processElement();
            return null;
        }

        public void processElement() throws Exception {
            collector.setTimestamp(element);
            userFunction.flatMap(element.getValue(), collector);
        }
    }
}
