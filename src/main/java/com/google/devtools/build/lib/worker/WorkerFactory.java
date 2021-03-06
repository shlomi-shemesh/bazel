// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker;

import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.vfs.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/**
 * Factory used by the pool to create / destroy / validate worker processes.
 */
final class WorkerFactory extends BaseKeyedPooledObjectFactory<WorkerKey, Worker> {

  // It's fine to use an AtomicInteger here (which is 32-bit), because it is only incremented when
  // spawning a new worker, thus even under worst-case circumstances and buggy workers quitting
  // after each action, this should never overflow.
  private static final AtomicInteger pidCounter = new AtomicInteger();

  private WorkerOptions workerOptions;
  private final Path workerBaseDir;
  private Reporter reporter;

  public WorkerFactory(WorkerOptions workerOptions, Path workerBaseDir) {
    this.workerOptions = workerOptions;
    this.workerBaseDir = workerBaseDir;
  }

  public void setReporter(Reporter reporter) {
    this.reporter = reporter;
  }

  public void setOptions(WorkerOptions workerOptions) {
    this.workerOptions = workerOptions;
  }

  @Override
  public Worker create(WorkerKey key) throws Exception {
    int workerId = pidCounter.getAndIncrement();
    Path logFile =
        workerBaseDir.getRelative("worker-" + workerId + "-" + key.getMnemonic() + ".log");

    Worker worker;
    boolean sandboxed = workerOptions.workerSandboxing || key.mustBeSandboxed();
    if (sandboxed) {
      Path workDir = workerBaseDir.getRelative("worker-" + workerId + "-" + key.getMnemonic());
      worker = new SandboxedWorker(key, workerId, workDir, logFile);
    } else {
      worker = new Worker(key, workerId, key.getExecRoot(), logFile);
    }
    worker.prepareExecution(key);
    worker.createProcess();
    if (workerOptions.workerVerbose) {
      reporter.handle(
          Event.info(
              String.format(
                  "Created new %s %s worker (id %d), logging to %s",
                  sandboxed ? "sandboxed" : "non-sandboxed",
                  key.getMnemonic(),
                  workerId,
                  logFile)));
    }
    return worker;
  }

  /**
   * Use the DefaultPooledObject implementation.
   */
  @Override
  public PooledObject<Worker> wrap(Worker worker) {
    return new DefaultPooledObject<>(worker);
  }

  /**
   * When a worker process is discarded, destroy its process, too.
   */
  @Override
  public void destroyObject(WorkerKey key, PooledObject<Worker> p) throws Exception {
    if (workerOptions.workerVerbose) {
      reporter.handle(
          Event.info(
              String.format(
                  "Destroying %s worker (id %d)", key.getMnemonic(), p.getObject().getWorkerId())));
    }
    p.getObject().destroy();
  }

  /**
   * The worker is considered to be valid when its files have not changed on disk and its process is
   * still alive.
   */
  @Override
  public boolean validateObject(WorkerKey key, PooledObject<Worker> p) {
    Worker worker = p.getObject();
    boolean hashMatches = key.getWorkerFilesHash().equals(worker.getWorkerFilesHash());

    if (reporter != null && !hashMatches) {
      reporter.handle(
          Event.warn(
              String.format(
                  "%s worker (id %d) can no longer be used, because its files have changed on"
                      + " disk [%s -> %s]",
                  key.getMnemonic(),
                  worker.getWorkerId(),
                  worker.getWorkerFilesHash(),
                  key.getWorkerFilesHash())));
    }

    return hashMatches;
  }
}
