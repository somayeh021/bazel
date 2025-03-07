// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.skyframe.Differencer.Diff;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An in-memory {@link MemoizingEvaluator} that uses the eager invalidation strategy. This class is,
 * by itself, not thread-safe. Neither is it thread-safe to use this class in parallel with any of
 * the returned graphs. However, it is allowed to access the graph from multiple threads as long as
 * that does not happen in parallel with an {@link #evaluate} call.
 *
 * <p>This memoizing evaluator uses a monotonically increasing {@link IntVersion} for incremental
 * evaluations and {@link Version#constant} for non-incremental evaluations.
 */
public final class InMemoryMemoizingEvaluator
    extends AbstractIncrementalInMemoryMemoizingEvaluator {
  // Not final only for testing.
  private InMemoryGraph graph;

  private final AtomicBoolean evaluating = new AtomicBoolean(false);

  public InMemoryMemoizingEvaluator(
      Map<SkyFunctionName, SkyFunction> skyFunctions, Differencer differencer) {
    this(skyFunctions, differencer, EvaluationProgressReceiver.NULL);
  }

  public InMemoryMemoizingEvaluator(
      Map<SkyFunctionName, SkyFunction> skyFunctions,
      Differencer differencer,
      EvaluationProgressReceiver progressReceiver) {
    this(
        skyFunctions,
        differencer,
        progressReceiver,
        GraphInconsistencyReceiver.THROWING,
        EventFilter.FULL_STORAGE,
        new EmittedEventState(),
        /* keepEdges= */ true,
        /* usePooledInterning= */ true);
  }

  public InMemoryMemoizingEvaluator(
      Map<SkyFunctionName, SkyFunction> skyFunctions,
      Differencer differencer,
      EvaluationProgressReceiver progressReceiver,
      GraphInconsistencyReceiver graphInconsistencyReceiver,
      EventFilter eventFilter,
      EmittedEventState emittedEventState,
      boolean keepEdges,
      boolean usePooledInterning) {
    super(
        ImmutableMap.copyOf(skyFunctions),
        differencer,
        new DirtyAndInflightTrackingProgressReceiver(progressReceiver),
        eventFilter,
        emittedEventState,
        graphInconsistencyReceiver,
        keepEdges);
    this.graph =
        keepEdges
            ? InMemoryGraph.create(usePooledInterning)
            : InMemoryGraph.createEdgeless(usePooledInterning);
  }

  @Override
  public <T extends SkyValue> EvaluationResult<T> evaluate(
      Iterable<? extends SkyKey> roots, EvaluationContext evaluationContext)
      throws InterruptedException {
    // NOTE: Performance critical code. See bug "Null build performance parity".
    Version graphVersion = getNextGraphVersion();
    setAndCheckEvaluateState(true, roots);

    // Mark for removal any nodes from the previous evaluation that were still inflight or were
    // rewound but did not complete successfully. When the invalidator runs, it will delete the
    // reverse transitive closure.
    valuesToDelete.addAll(progressReceiver.getAndClearInflightKeys());
    valuesToDelete.addAll(progressReceiver.getAndClearUnsuccessfullyRewoundKeys());
    try {
      // The RecordingDifferencer implementation is not quite working as it should be at this point.
      // It clears the internal data structures after getDiff is called and will not return
      // diffs for historical versions. This makes the following code sensitive to interrupts.
      // Ideally we would simply not update lastGraphVersion if an interrupt occurs.
      Diff diff =
          differencer.getDiff(new DelegatingWalkableGraph(graph), lastGraphVersion, graphVersion);
      if (!diff.isEmpty() || !valuesToInject.isEmpty() || !valuesToDelete.isEmpty()) {
        valuesToInject.putAll(diff.changedKeysWithNewValues());
        invalidate(diff.changedKeysWithoutNewValues());
        pruneInjectedValues(valuesToInject);
        invalidate(valuesToInject.keySet());

        performInvalidation();
        injectValues(graphVersion);
      }

      EvaluationResult<T> result;
      try (SilentCloseable c = Profiler.instance().profile("ParallelEvaluator.eval")) {
        ParallelEvaluator evaluator =
            new ParallelEvaluator(
                graph,
                graphVersion,
                Version.minimal(),
                skyFunctions,
                evaluationContext.getEventHandler(),
                emittedEventState,
                eventFilter,
                ErrorInfoManager.UseChildErrorInfoIfNecessary.INSTANCE,
                evaluationContext.getKeepGoing(),
                progressReceiver,
                graphInconsistencyReceiver,
                evaluationContext
                    .getExecutor()
                    .orElseGet(
                        () ->
                            AbstractQueueVisitor.create(
                                "skyframe-evaluator",
                                evaluationContext.getParallelism(),
                                ParallelEvaluatorErrorClassifier.instance())),
                new SimpleCycleDetector(),
                evaluationContext.mergingSkyframeAnalysisExecutionPhases(),
                evaluationContext.getUnnecessaryTemporaryStateDropperReceiver());
        result = evaluator.eval(roots);
      }
      return EvaluationResult.<T>builder()
          .mergeFrom(result)
          .setWalkableGraph(new DelegatingWalkableGraph(graph))
          .build();
    } finally {
      if (keepEdges) {
        lastGraphVersion = (IntVersion) graphVersion;
      }
      setAndCheckEvaluateState(false, roots);
    }
  }

  private void setAndCheckEvaluateState(boolean newValue, Object requestInfo) {
    Preconditions.checkState(evaluating.getAndSet(newValue) != newValue,
        "Re-entrant evaluation for request: %s", requestInfo);
  }

  @Override
  public void postLoggingStats(ExtendedEventHandler eventHandler) {
    eventHandler.post(new SkyframeGraphStatsEvent(graph.valuesSize()));
  }

  @Override
  public void injectGraphTransformerForTesting(GraphTransformerForTesting transformer) {
    this.graph = transformer.transform(this.graph);
  }

  @Override
  public InMemoryGraph getInMemoryGraph() {
    return graph;
  }

  public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctionsForTesting() {
    return skyFunctions;
  }
}
