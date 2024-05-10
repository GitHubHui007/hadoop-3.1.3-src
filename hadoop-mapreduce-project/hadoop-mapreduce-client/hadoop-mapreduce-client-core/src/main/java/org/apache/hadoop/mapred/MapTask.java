/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.google.common.primitives.Longs;
import com.google.common.primitives.UnsignedBytes;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystem.Statistics;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.*;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.mapred.IFile.Writer;
import org.apache.hadoop.mapred.Merger.Segment;
import org.apache.hadoop.mapred.SortedRanges.SkipRangeIterator;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskCounter;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormatCounter;
import org.apache.hadoop.mapreduce.lib.map.WrappedMapper;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormatCounter;
import org.apache.hadoop.mapreduce.split.JobSplit.TaskSplitIndex;
import org.apache.hadoop.mapreduce.task.MapContextImpl;
import org.apache.hadoop.mapreduce.CryptoUtils;
import org.apache.hadoop.util.IndexedSortable;
import org.apache.hadoop.util.IndexedSorter;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.QuickSort;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringInterner;
import org.apache.hadoop.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Map task.
 */
@InterfaceAudience.LimitedPrivate({"MapReduce"})
@InterfaceStability.Unstable
public class MapTask extends Task {
  /**
   * The size of each record in the index file for the map-outputs.
   */
  public static final int MAP_OUTPUT_INDEX_RECORD_LENGTH = 24;

  // The minimum permissions needed for a shuffle output file.
  private static final FsPermission SHUFFLE_OUTPUT_PERM =
          new FsPermission((short) 0640);

  private TaskSplitIndex splitMetaInfo = new TaskSplitIndex();
  private final static int APPROX_HEADER_LENGTH = 150;

  private static final Logger LOG =
          LoggerFactory.getLogger(MapTask.class.getName());

  private Progress mapPhase;
  private Progress sortPhase;

  {   // set phase for this task
    setPhase(TaskStatus.Phase.MAP);
    getProgress().setStatus("map");
  }

  //TODO-PZH 记录当前Map需要处理的切片数据长度，用于RAA算法计算环形缓冲区容量
  private long inputDataLength;

  private List<Long> sortElapseTimeList = new ArrayList<>();
  private List<Long> sortAndSpillElapseTimeList = new ArrayList<>();

  public MapTask() {
    super();
  }

  public MapTask(String jobFile, TaskAttemptID taskId,
                 int partition, TaskSplitIndex splitIndex,
                 int numSlotsRequired) {
    super(jobFile, taskId, partition, numSlotsRequired);
    this.splitMetaInfo = splitIndex;
  }

  @Override
  public boolean isMapTask() {
    return true;
  }

  @Override
  public void localizeConfiguration(JobConf conf)
          throws IOException {
    super.localizeConfiguration(conf);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);
    if (isMapOrReduce()) {
      splitMetaInfo.write(out);
      splitMetaInfo = null;
    }
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);
    if (isMapOrReduce()) {
      splitMetaInfo.readFields(in);
    }
  }

  /**
   * This class wraps the user's record reader to update the counters and progress
   * as records are read.
   *
   * @param <K>
   * @param <V>
   */
  class TrackedRecordReader<K, V>
          implements RecordReader<K, V> {
    private RecordReader<K, V> rawIn;
    private Counters.Counter fileInputByteCounter;
    private Counters.Counter inputRecordCounter;
    private TaskReporter reporter;
    private long bytesInPrev = -1;
    private long bytesInCurr = -1;
    private final List<Statistics> fsStats;

    TrackedRecordReader(TaskReporter reporter, JobConf job)
            throws IOException {
      inputRecordCounter = reporter.getCounter(TaskCounter.MAP_INPUT_RECORDS);
      fileInputByteCounter = reporter.getCounter(FileInputFormatCounter.BYTES_READ);
      this.reporter = reporter;

      List<Statistics> matchedStats = null;
      if (this.reporter.getInputSplit() instanceof FileSplit) {
        matchedStats = getFsStatistics(((FileSplit) this.reporter
                .getInputSplit()).getPath(), job);
      }
      fsStats = matchedStats;

      bytesInPrev = getInputBytes(fsStats);
      rawIn = job.getInputFormat().getRecordReader(reporter.getInputSplit(),
              job, reporter);
      bytesInCurr = getInputBytes(fsStats);
      fileInputByteCounter.increment(bytesInCurr - bytesInPrev);
    }

    public K createKey() {
      return rawIn.createKey();
    }

    public V createValue() {
      return rawIn.createValue();
    }

    public synchronized boolean next(K key, V value)
            throws IOException {
      boolean ret = moveToNext(key, value);
      if (ret) {
        incrCounters();
      }
      return ret;
    }

    protected void incrCounters() {
      inputRecordCounter.increment(1);
    }

    protected synchronized boolean moveToNext(K key, V value)
            throws IOException {
      bytesInPrev = getInputBytes(fsStats);
      boolean ret = rawIn.next(key, value);
      bytesInCurr = getInputBytes(fsStats);
      fileInputByteCounter.increment(bytesInCurr - bytesInPrev);
      reporter.setProgress(getProgress());
      return ret;
    }

    public long getPos() throws IOException {
      return rawIn.getPos();
    }

    public void close() throws IOException {
      bytesInPrev = getInputBytes(fsStats);
      rawIn.close();
      bytesInCurr = getInputBytes(fsStats);
      fileInputByteCounter.increment(bytesInCurr - bytesInPrev);
    }

    public float getProgress() throws IOException {
      return rawIn.getProgress();
    }

    TaskReporter getTaskReporter() {
      return reporter;
    }

    private long getInputBytes(List<Statistics> stats) {
      if (stats == null) return 0;
      long bytesRead = 0;
      for (Statistics stat : stats) {
        bytesRead = bytesRead + stat.getBytesRead();
      }
      return bytesRead;
    }
  }

  /**
   * This class skips the records based on the failed ranges from previous
   * attempts.
   */
  class SkippingRecordReader<K, V> extends TrackedRecordReader<K, V> {
    private SkipRangeIterator skipIt;
    private SequenceFile.Writer skipWriter;
    private boolean toWriteSkipRecs;
    private TaskUmbilicalProtocol umbilical;
    private Counters.Counter skipRecCounter;
    private long recIndex = -1;

    SkippingRecordReader(TaskUmbilicalProtocol umbilical,
                         TaskReporter reporter, JobConf job) throws IOException {
      super(reporter, job);
      this.umbilical = umbilical;
      this.skipRecCounter = reporter.getCounter(TaskCounter.MAP_SKIPPED_RECORDS);
      this.toWriteSkipRecs = toWriteSkipRecs() &&
              SkipBadRecords.getSkipOutputPath(conf) != null;
      skipIt = getSkipRanges().skipRangeIterator();
    }

    public synchronized boolean next(K key, V value)
            throws IOException {
      if (!skipIt.hasNext()) {
        LOG.warn("Further records got skipped.");
        return false;
      }
      boolean ret = moveToNext(key, value);
      long nextRecIndex = skipIt.next();
      long skip = 0;
      while (recIndex < nextRecIndex && ret) {
        if (toWriteSkipRecs) {
          writeSkippedRec(key, value);
        }
        ret = moveToNext(key, value);
        skip++;
      }
      //close the skip writer once all the ranges are skipped
      if (skip > 0 && skipIt.skippedAllRanges() && skipWriter != null) {
        skipWriter.close();
      }
      skipRecCounter.increment(skip);
      reportNextRecordRange(umbilical, recIndex);
      if (ret) {
        incrCounters();
      }
      return ret;
    }

    protected synchronized boolean moveToNext(K key, V value)
            throws IOException {
      recIndex++;
      return super.moveToNext(key, value);
    }

    @SuppressWarnings("unchecked")
    private void writeSkippedRec(K key, V value) throws IOException {
      if (skipWriter == null) {
        Path skipDir = SkipBadRecords.getSkipOutputPath(conf);
        Path skipFile = new Path(skipDir, getTaskID().toString());
        skipWriter =
                SequenceFile.createWriter(
                        skipFile.getFileSystem(conf), conf, skipFile,
                        (Class<K>) createKey().getClass(),
                        (Class<V>) createValue().getClass(),
                        CompressionType.BLOCK, getTaskReporter());
      }
      skipWriter.append(key, value);
    }
  }

  @Override
  public void run(final JobConf job, final TaskUmbilicalProtocol umbilical)
          throws IOException, ClassNotFoundException, InterruptedException {
    this.umbilical = umbilical;

    if (isMapTask()) {
      // If there are no reducers then there won't be any sort. Hence the map
      // phase will govern the entire attempt's progress.
      if (conf.getNumReduceTasks() == 0) {
        mapPhase = getProgress().addPhase("map", 1.0f);
      } else {
        // If there are reducers then the entire attempt's progress will be
        // split between the map phase (67%) and the sort phase (33%).
        mapPhase = getProgress().addPhase("map", 0.667f);
        sortPhase = getProgress().addPhase("sort", 0.333f);
      }
    }
    TaskReporter reporter = startReporter(umbilical);

    boolean useNewApi = job.getUseNewMapper();
    initialize(job, getJobID(), reporter, useNewApi);

    // check if it is a cleanupJobTask
    if (jobCleanup) {
      runJobCleanupTask(umbilical, reporter);
      return;
    }
    if (jobSetup) {
      runJobSetupTask(umbilical, reporter);
      return;
    }
    if (taskCleanup) {
      runTaskCleanupTask(umbilical, reporter);
      return;
    }

    if (useNewApi) {
      runNewMapper(job, splitMetaInfo, umbilical, reporter);
    } else {
      runOldMapper(job, splitMetaInfo, umbilical, reporter);
    }
    done(umbilical, reporter);
  }

  public Progress getSortPhase() {
    return sortPhase;
  }

  @SuppressWarnings("unchecked")
  private <T> T getSplitDetails(Path file, long offset)
          throws IOException {
    FileSystem fs = file.getFileSystem(conf);
    FSDataInputStream inFile = fs.open(file);
    inFile.seek(offset);
    String className = StringInterner.weakIntern(Text.readString(inFile));
    Class<T> cls;
    try {
      cls = (Class<T>) conf.getClassByName(className);
    } catch (ClassNotFoundException ce) {
      IOException wrap = new IOException("Split class " + className +
              " not found");
      wrap.initCause(ce);
      throw wrap;
    }
    SerializationFactory factory = new SerializationFactory(conf);
    Deserializer<T> deserializer =
            (Deserializer<T>) factory.getDeserializer(cls);
    deserializer.open(inFile);
    T split = deserializer.deserialize(null);
    long pos = inFile.getPos();
    getCounters().findCounter(
            TaskCounter.SPLIT_RAW_BYTES).increment(pos - offset);
    inFile.close();
    return split;
  }

  @SuppressWarnings("unchecked")
  private <KEY, VALUE> MapOutputCollector<KEY, VALUE>
  createSortingCollector(JobConf job, TaskReporter reporter)
          throws IOException, ClassNotFoundException {
    MapOutputCollector.Context context =
            new MapOutputCollector.Context(this, job, reporter);

    Class<?>[] collectorClasses = job.getClasses(
            JobContext.MAP_OUTPUT_COLLECTOR_CLASS_ATTR, MapOutputBuffer.class);
    int remainingCollectors = collectorClasses.length;
    Exception lastException = null;
    for (Class clazz : collectorClasses) {
      try {
        if (!MapOutputCollector.class.isAssignableFrom(clazz)) {
          throw new IOException("Invalid output collector class: " + clazz.getName() +
                  " (does not implement MapOutputCollector)");
        }
        Class<? extends MapOutputCollector> subclazz =
                clazz.asSubclass(MapOutputCollector.class);
        LOG.debug("Trying map output collector class: " + subclazz.getName());
        MapOutputCollector<KEY, VALUE> collector =
                ReflectionUtils.newInstance(subclazz, job);
        //TODO-PZH 环形缓冲区初始化
        collector.init(context);
        LOG.info("Map output collector class = " + collector.getClass().getName());
        return collector;
      } catch (Exception e) {
        String msg = "Unable to initialize MapOutputCollector " + clazz.getName();
        if (--remainingCollectors > 0) {
          msg += " (" + remainingCollectors + " more collector(s) to try)";
        }
        lastException = e;
        LOG.warn(msg, e);
      }
    }

    if (lastException != null) {
      throw new IOException("Initialization of all the collectors failed. " +
              "Error in last collector was:" + lastException.toString(),
              lastException);
    } else {
      throw new IOException("Initialization of all the collectors failed.");
    }
  }

  @SuppressWarnings("unchecked")
  private <INKEY, INVALUE, OUTKEY, OUTVALUE>
  void runOldMapper(final JobConf job,
                    final TaskSplitIndex splitIndex,
                    final TaskUmbilicalProtocol umbilical,
                    TaskReporter reporter
  ) throws IOException, InterruptedException,
          ClassNotFoundException {
    InputSplit inputSplit = getSplitDetails(new Path(splitIndex.getSplitLocation()),
            splitIndex.getStartOffset());

    updateJobWithSplit(job, inputSplit);
    reporter.setInputSplit(inputSplit);

    RecordReader<INKEY, INVALUE> in = isSkipping() ?
            new SkippingRecordReader<INKEY, INVALUE>(umbilical, reporter, job) :
            new TrackedRecordReader<INKEY, INVALUE>(reporter, job);
    job.setBoolean(JobContext.SKIP_RECORDS, isSkipping());


    int numReduceTasks = conf.getNumReduceTasks();
    LOG.info("numReduceTasks: " + numReduceTasks);
    MapOutputCollector<OUTKEY, OUTVALUE> collector = null;
    if (numReduceTasks > 0) {
      collector = createSortingCollector(job, reporter);
    } else {
      collector = new DirectMapOutputCollector<OUTKEY, OUTVALUE>();
      MapOutputCollector.Context context =
              new MapOutputCollector.Context(this, job, reporter);
      collector.init(context);
    }
    MapRunnable<INKEY, INVALUE, OUTKEY, OUTVALUE> runner =
            ReflectionUtils.newInstance(job.getMapRunnerClass(), job);

    try {
      runner.run(in, new OldOutputCollector(collector, conf), reporter);
      mapPhase.complete();
      // start the sort phase only if there are reducers
      if (numReduceTasks > 0) {
        setPhase(TaskStatus.Phase.SORT);
      }
      statusUpdate(umbilical);
      collector.flush();

      in.close();
      in = null;

      collector.close();
      collector = null;
    } finally {
      closeQuietly(in);
      closeQuietly(collector);
    }
  }

  /**
   * Update the job with details about the file split
   *
   * @param job        the job configuration to update
   * @param inputSplit the file split
   */
  private void updateJobWithSplit(final JobConf job, InputSplit inputSplit) {
    if (inputSplit instanceof FileSplit) {
      FileSplit fileSplit = (FileSplit) inputSplit;
      job.set(JobContext.MAP_INPUT_FILE, fileSplit.getPath().toString());
      job.setLong(JobContext.MAP_INPUT_START, fileSplit.getStart());
      job.setLong(JobContext.MAP_INPUT_PATH, fileSplit.getLength());
    }
    LOG.info("Processing split: " + inputSplit);
  }

  static class NewTrackingRecordReader<K, V>
          extends org.apache.hadoop.mapreduce.RecordReader<K, V> {
    private final org.apache.hadoop.mapreduce.RecordReader<K, V> real;
    private final org.apache.hadoop.mapreduce.Counter inputRecordCounter;
    private final org.apache.hadoop.mapreduce.Counter fileInputByteCounter;
    private final TaskReporter reporter;
    private final List<Statistics> fsStats;

    NewTrackingRecordReader(org.apache.hadoop.mapreduce.InputSplit split,
                            org.apache.hadoop.mapreduce.InputFormat<K, V> inputFormat,
                            TaskReporter reporter,
                            org.apache.hadoop.mapreduce.TaskAttemptContext taskContext)
            throws InterruptedException, IOException {
      this.reporter = reporter;
      this.inputRecordCounter = reporter
              .getCounter(TaskCounter.MAP_INPUT_RECORDS);
      this.fileInputByteCounter = reporter
              .getCounter(FileInputFormatCounter.BYTES_READ);

      List<Statistics> matchedStats = null;
      if (split instanceof org.apache.hadoop.mapreduce.lib.input.FileSplit) {
        matchedStats = getFsStatistics(((org.apache.hadoop.mapreduce.lib.input.FileSplit) split)
                .getPath(), taskContext.getConfiguration());
      }
      fsStats = matchedStats;

      long bytesInPrev = getInputBytes(fsStats);
      this.real = inputFormat.createRecordReader(split, taskContext);
      long bytesInCurr = getInputBytes(fsStats);
      fileInputByteCounter.increment(bytesInCurr - bytesInPrev);
    }

    @Override
    public void close() throws IOException {
      long bytesInPrev = getInputBytes(fsStats);
      real.close();
      long bytesInCurr = getInputBytes(fsStats);
      fileInputByteCounter.increment(bytesInCurr - bytesInPrev);
    }

    @Override
    public K getCurrentKey() throws IOException, InterruptedException {
      return real.getCurrentKey();
    }

    @Override
    public V getCurrentValue() throws IOException, InterruptedException {
      return real.getCurrentValue();
    }

    @Override
    public float getProgress() throws IOException, InterruptedException {
      return real.getProgress();
    }

    @Override
    public void initialize(org.apache.hadoop.mapreduce.InputSplit split,
                           org.apache.hadoop.mapreduce.TaskAttemptContext context
    ) throws IOException, InterruptedException {
      long bytesInPrev = getInputBytes(fsStats);
      real.initialize(split, context);
      long bytesInCurr = getInputBytes(fsStats);
      fileInputByteCounter.increment(bytesInCurr - bytesInPrev);
    }

    @Override
    public boolean nextKeyValue() throws IOException, InterruptedException {
      long bytesInPrev = getInputBytes(fsStats);
      boolean result = real.nextKeyValue();
      long bytesInCurr = getInputBytes(fsStats);
      if (result) {
        inputRecordCounter.increment(1);
      }
      fileInputByteCounter.increment(bytesInCurr - bytesInPrev);
      reporter.setProgress(getProgress());
      return result;
    }

    private long getInputBytes(List<Statistics> stats) {
      if (stats == null) return 0;
      long bytesRead = 0;
      for (Statistics stat : stats) {
        bytesRead = bytesRead + stat.getBytesRead();
      }
      return bytesRead;
    }
  }

  /**
   * Since the mapred and mapreduce Partitioners don't share a common interface
   * (JobConfigurable is deprecated and a subtype of mapred.Partitioner), the
   * partitioner lives in Old/NewOutputCollector. Note that, for map-only jobs,
   * the configured partitioner should not be called. It's common for
   * partitioners to compute a result mod numReduces, which causes a div0 error
   */
  private static class OldOutputCollector<K, V> implements OutputCollector<K, V> {
    private final Partitioner<K, V> partitioner;
    private final MapOutputCollector<K, V> collector;
    private final int numPartitions;

    @SuppressWarnings("unchecked")
    OldOutputCollector(MapOutputCollector<K, V> collector, JobConf conf) {
      numPartitions = conf.getNumReduceTasks();
      if (numPartitions > 1) {
        partitioner = (Partitioner<K, V>)
                ReflectionUtils.newInstance(conf.getPartitionerClass(), conf);
      } else {
        partitioner = new Partitioner<K, V>() {
          @Override
          public void configure(JobConf job) {
          }

          @Override
          public int getPartition(K key, V value, int numPartitions) {
            return numPartitions - 1;
          }
        };
      }
      this.collector = collector;
    }

    @Override
    public void collect(K key, V value) throws IOException {
      try {
        collector.collect(key, value,
                partitioner.getPartition(key, value, numPartitions));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupt exception", ie);
      }
    }
  }

  private class NewDirectOutputCollector<K, V>
          extends org.apache.hadoop.mapreduce.RecordWriter<K, V> {
    private final org.apache.hadoop.mapreduce.RecordWriter out;

    private final TaskReporter reporter;

    private final Counters.Counter mapOutputRecordCounter;
    private final Counters.Counter fileOutputByteCounter;
    private final List<Statistics> fsStats;

    @SuppressWarnings("unchecked")
    NewDirectOutputCollector(MRJobConfig jobContext,
                             JobConf job, TaskUmbilicalProtocol umbilical, TaskReporter reporter)
            throws IOException, ClassNotFoundException, InterruptedException {
      this.reporter = reporter;
      mapOutputRecordCounter = reporter
              .getCounter(TaskCounter.MAP_OUTPUT_RECORDS);
      fileOutputByteCounter = reporter
              .getCounter(FileOutputFormatCounter.BYTES_WRITTEN);

      List<Statistics> matchedStats = null;
      if (outputFormat instanceof org.apache.hadoop.mapreduce.lib.output.FileOutputFormat) {
        matchedStats = getFsStatistics(org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
                .getOutputPath(taskContext), taskContext.getConfiguration());
      }
      fsStats = matchedStats;

      long bytesOutPrev = getOutputBytes(fsStats);
      out = outputFormat.getRecordWriter(taskContext);
      long bytesOutCurr = getOutputBytes(fsStats);
      fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(K key, V value)
            throws IOException, InterruptedException {
      reporter.progress();
      long bytesOutPrev = getOutputBytes(fsStats);
      out.write(key, value);
      long bytesOutCurr = getOutputBytes(fsStats);
      fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
      mapOutputRecordCounter.increment(1);
    }

    @Override
    public void close(TaskAttemptContext context)
            throws IOException, InterruptedException {
      reporter.progress();
      if (out != null) {
        long bytesOutPrev = getOutputBytes(fsStats);
        out.close(context);
        long bytesOutCurr = getOutputBytes(fsStats);
        fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
      }
    }

    private long getOutputBytes(List<Statistics> stats) {
      if (stats == null) return 0;
      long bytesWritten = 0;
      for (Statistics stat : stats) {
        bytesWritten = bytesWritten + stat.getBytesWritten();
      }
      return bytesWritten;
    }
  }

  private class NewOutputCollector<K, V>
          extends org.apache.hadoop.mapreduce.RecordWriter<K, V> {
    private final MapOutputCollector<K, V> collector;
    private final org.apache.hadoop.mapreduce.Partitioner<K, V> partitioner;
    private final int partitions;

    @SuppressWarnings("unchecked")
    NewOutputCollector(org.apache.hadoop.mapreduce.JobContext jobContext,
                       JobConf job,
                       TaskUmbilicalProtocol umbilical,
                       TaskReporter reporter
    ) throws IOException, ClassNotFoundException {
      collector = createSortingCollector(job, reporter);
      partitions = jobContext.getNumReduceTasks();
      if (partitions > 1) {
        partitioner = (org.apache.hadoop.mapreduce.Partitioner<K, V>)
                ReflectionUtils.newInstance(jobContext.getPartitionerClass(), job);
      } else {
        partitioner = new org.apache.hadoop.mapreduce.Partitioner<K, V>() {
          @Override
          public int getPartition(K key, V value, int numPartitions) {
            return partitions - 1;
          }
        };
      }
    }

    @Override
    public void write(K key, V value) throws IOException, InterruptedException {
      collector.collect(key, value,
              partitioner.getPartition(key, value, partitions));
    }

    @Override
    public void close(TaskAttemptContext context
    ) throws IOException, InterruptedException {
      try {
        //TODO-PZH 处理缓冲区中剩余的数据
        collector.flush();
      } catch (ClassNotFoundException cnf) {
        throw new IOException("can't find class ", cnf);
      }
      collector.close();
    }
  }

  @SuppressWarnings("unchecked")
  private <INKEY, INVALUE, OUTKEY, OUTVALUE>
  void runNewMapper(final JobConf job,
                    final TaskSplitIndex splitIndex,
                    final TaskUmbilicalProtocol umbilical,
                    TaskReporter reporter
  ) throws IOException, ClassNotFoundException,
          InterruptedException {
    // make a task context so we can get the classes
    org.apache.hadoop.mapreduce.TaskAttemptContext taskContext =
            new org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl(job,
                    getTaskID(),
                    reporter);
    // make a mapper
    org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE> mapper =
            (org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE>)
                    ReflectionUtils.newInstance(taskContext.getMapperClass(), job);
    // make the input format
    org.apache.hadoop.mapreduce.InputFormat<INKEY, INVALUE> inputFormat =
            (org.apache.hadoop.mapreduce.InputFormat<INKEY, INVALUE>)
                    ReflectionUtils.newInstance(taskContext.getInputFormatClass(), job);
    // rebuild the input split
    org.apache.hadoop.mapreduce.InputSplit split = null;
    split = getSplitDetails(new Path(splitIndex.getSplitLocation()),
            splitIndex.getStartOffset());
    LOG.info("Processing split: " + split);

    //TODO-PZH 从切片信息中获取当前map需要处理的分片长度
    this.inputDataLength = split.getLength();

    org.apache.hadoop.mapreduce.RecordReader<INKEY, INVALUE> input =
            new NewTrackingRecordReader<INKEY, INVALUE>
                    (split, inputFormat, reporter, taskContext);

    job.setBoolean(JobContext.SKIP_RECORDS, isSkipping());
    org.apache.hadoop.mapreduce.RecordWriter output = null;

    // get an output object
    if (job.getNumReduceTasks() == 0) {
      output =
              new NewDirectOutputCollector(taskContext, job, umbilical, reporter);
    } else {
      //TODO-PZH 创建环形缓冲区
      output = new NewOutputCollector(taskContext, job, umbilical, reporter);
    }

    org.apache.hadoop.mapreduce.MapContext<INKEY, INVALUE, OUTKEY, OUTVALUE>
            mapContext =
            new MapContextImpl<INKEY, INVALUE, OUTKEY, OUTVALUE>(job, getTaskID(),
                    input, output,
                    committer,
                    reporter, split);

    org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE>.Context
            mapperContext =
            new WrappedMapper<INKEY, INVALUE, OUTKEY, OUTVALUE>().getMapContext(
                    mapContext);

    try {
      input.initialize(split, mapperContext);
      //TODO-PZH 开始运行MapTask
      mapper.run(mapperContext);
      mapPhase.complete();
      setPhase(TaskStatus.Phase.SORT);
      statusUpdate(umbilical);
      input.close();
      input = null;
      //TODO-PZH 开始回收缓冲区
      output.close(mapperContext);
      output = null;
    } finally {
      closeQuietly(input);
      closeQuietly(output, mapperContext);
      if (job.getBoolean(MRJobConfig.PZH_MR_PRINT_MAP_SORT_ELAPSE_ENABLED, MRJobConfig.DEFAULT_PZH_MR_PRINT_MAP_SORT_ELAPSE_ENABLED)) {
        LongSummaryStatistics statistics = sortElapseTimeList.stream().collect(Collectors.summarizingLong(x -> x));
        LOG.info("[SortElapse-mapID]:" + this.getTaskID());
        LOG.info("[SortElapse-detail]:[" + sortElapseTimeList.stream().map(x -> x + "").collect(Collectors.joining(",")) + "]");
        LOG.info("[SortElapse-statics:]" + statistics);

        LongSummaryStatistics statistics2 = sortAndSpillElapseTimeList.stream().collect(Collectors.summarizingLong(x -> x));
        LOG.info("[SortAndSpillElapse-detail]:[" + sortAndSpillElapseTimeList.stream().map(x -> x + "").collect(Collectors.joining(",")) + "]");
        LOG.info("[SortAndSpillElapse-statics:]" + statistics2);

        System.out.println(statistics);
        System.out.println(statistics2);
      }
      sortElapseTimeList.clear();
      sortAndSpillElapseTimeList.clear();
    }
  }

  class DirectMapOutputCollector<K, V>
          implements MapOutputCollector<K, V> {

    private RecordWriter<K, V> out = null;

    private TaskReporter reporter = null;

    private Counters.Counter mapOutputRecordCounter;
    private Counters.Counter fileOutputByteCounter;
    private List<Statistics> fsStats;

    public DirectMapOutputCollector() {
    }

    @SuppressWarnings("unchecked")
    public void init(MapOutputCollector.Context context
    ) throws IOException, ClassNotFoundException {
      this.reporter = context.getReporter();
      JobConf job = context.getJobConf();
      String finalName = getOutputName(getPartition());
      FileSystem fs = FileSystem.get(job);

      OutputFormat<K, V> outputFormat = job.getOutputFormat();
      mapOutputRecordCounter = reporter.getCounter(TaskCounter.MAP_OUTPUT_RECORDS);

      fileOutputByteCounter = reporter
              .getCounter(FileOutputFormatCounter.BYTES_WRITTEN);

      List<Statistics> matchedStats = null;
      if (outputFormat instanceof FileOutputFormat) {
        matchedStats = getFsStatistics(FileOutputFormat.getOutputPath(job), job);
      }
      fsStats = matchedStats;

      long bytesOutPrev = getOutputBytes(fsStats);
      out = job.getOutputFormat().getRecordWriter(fs, job, finalName, reporter);
      long bytesOutCurr = getOutputBytes(fsStats);
      fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
    }

    public void close() throws IOException {
      if (this.out != null) {
        long bytesOutPrev = getOutputBytes(fsStats);
        out.close(this.reporter);
        long bytesOutCurr = getOutputBytes(fsStats);
        fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
      }

    }

    public void flush() throws IOException, InterruptedException,
            ClassNotFoundException {
    }

    public void collect(K key, V value, int partition) throws IOException {
      reporter.progress();
      long bytesOutPrev = getOutputBytes(fsStats);
      out.write(key, value);
      long bytesOutCurr = getOutputBytes(fsStats);
      fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
      mapOutputRecordCounter.increment(1);
    }

    private long getOutputBytes(List<Statistics> stats) {
      if (stats == null) return 0;
      long bytesWritten = 0;
      for (Statistics stat : stats) {
        bytesWritten = bytesWritten + stat.getBytesWritten();
      }
      return bytesWritten;
    }
  }

  @InterfaceAudience.LimitedPrivate({"MapReduce"})
  @InterfaceStability.Unstable
  public static class MapOutputBuffer<K extends Object, V extends Object>
          implements MapOutputCollector<K, V>, IndexedSortable {
    private int partitions;
    private JobConf job;
    private TaskReporter reporter;
    private Class<K> keyClass;
    private Class<V> valClass;
    private RawComparator<K> comparator;
    private SerializationFactory serializationFactory;
    private Serializer<K> keySerializer;
    private Serializer<V> valSerializer;
    private CombinerRunner<K, V> combinerRunner;
    private CombineOutputCollector<K, V> combineCollector;

    // Compression for map-outputs
    private CompressionCodec codec;

    // k/v accounting
    private IntBuffer kvmeta; // metadata overlay on backing store
    private LongBuffer lkvmeta; // 当使用直接内存时，使用LongBuffer减少数据移动时的循环次数

    int kvstart;            // marks origin of spill metadata
    int kvend;              // marks end of spill metadata
    int kvindex;            // marks end of fully serialized records

    int equator;            // marks origin of meta/serialization
    int bufstart;           // marks beginning of spill
    int bufend;             // marks beginning of collectable
    int bufmark;            // marks end of record
    int bufindex;           // marks end of collected
    int bufvoid;            // marks the point where we should stop
    // reading at the end of the buffer
    //堆外内存相关补充变量
    boolean isUseDirectMemory = false; //是否使用直接内存
    int buflength;                 //the length of kvBuffer or kvDirectBuffer
    ByteBuffer kvDirectBuffer;   //direct main output buffer
    //堆外内存相关补充变量

    byte[] kvbuffer;        // main output buffer
    private final byte[] b0 = new byte[0];

    private static final int VALSTART = 0;         // val offset in acct
    private static final int KEYSTART = 1;         // key offset in acct
    private static final int PARTITION = 2;        // partition offset in acct
    private static final int VALLEN = 3;           // length of value
    private static final int NMETA = 4;            // num meta ints
    private static final int METASIZE = NMETA * 4; // size in bytes

    // spill accounting
    private int maxRec;
    private int softLimit;
    boolean spillInProgress;
    ;
    int bufferRemaining;
    volatile Throwable sortSpillException = null;

    int numSpills = 0;
    private int minSpillsForCombine;
    private IndexedSorter sorter;
    final ReentrantLock spillLock = new ReentrantLock();
    final Condition spillDone = spillLock.newCondition();
    final Condition spillReady = spillLock.newCondition();
    final BlockingBuffer bb = new BlockingBuffer();
    volatile boolean spillThreadRunning = false;
    final SpillThread spillThread = new SpillThread();

    private FileSystem rfs;

    // Counters
    private Counters.Counter mapOutputByteCounter;
    private Counters.Counter mapOutputRecordCounter;
    private Counters.Counter fileOutputByteCounter;

    final ArrayList<SpillRecord> indexCacheList =
            new ArrayList<SpillRecord>();
    private int totalIndexCacheMemory;
    private int indexCacheMemoryLimit;
    private static final int INDEX_CACHE_MEMORY_LIMIT_DEFAULT = 1024 * 1024;

    private MapTask mapTask;
    private MapOutputFile mapOutputFile;
    private Progress sortPhase;
    private Counters.Counter spilledRecordsCounter;

    public MapOutputBuffer() {
    }

    /**
     * 计算最优环形缓冲区容量
     * Ratio策略
     *
     * @return 第一个参数为环形缓冲区容量、第二个参数为环形缓冲区溢写比例
     */
    private float[] MMRAAWithProportionalStrategy(int sortmb, float spillper, Boolean isOptimizeSpiller) {
      float[] paras = new float[]{sortmb, spillper};
      long splits = job.getLong("mapreduce.splits", -1);
      long minimalSplitLength = job.getLong("mapreduce.minimalSplitLength", -1);
      long totalLength = job.getLong("mapreduce.splits.totalLength", -1);
      //因为Ratio算法的核心思想是将处理最小切片的Mapper过剩的sortmb资源均匀地分给其它几个Mapper，以此来提高其它几个Mapper的处理速度和降低处理最小切片的Mapper处理速度；
      //由于处理最小文件切片的Mapper往往会过早地完成计算而产生较长的等待时间来等待其它Mapper的计算完成；
      //因此将处理最小文件切片的Mapper所拥有的过剩的sortmb资源按照比例分给处理其它文件切片的Mapper以减少等待时间，以此来提高整个MapReduce的计算速度；
      long surplusb = (((long) sortmb << 20) - minimalSplitLength); //这里计算处理最小切片的Mapper过剩的sortmb大小
      //TODO-PZH 当逻辑分片数量>1以及surplusb>0时才开始计算
      if (splits > 1 && surplusb > 0) {
        //计算当前Mapper处理的文件切片大小占总文件大小的比例
        float ratio = mapTask.inputDataLength * 1.0f / totalLength;
        if (minimalSplitLength != mapTask.inputDataLength) {
          paras[0] = paras[0] + ((long) (ratio * surplusb) >> 20);
        } else {
          paras[0] = (minimalSplitLength + (long) (ratio * surplusb)) >> 20;
        }
        if (isOptimizeSpiller) {
          paras[1] = paras[1] + ((1.0f - paras[1]) * ratio);
        }
      }
      return paras;
    }

    /**
     * 计算最优环形缓冲区容量
     * Performance策略
     *
     * @return 第一个参数为环形缓冲区容量、第二个参数为环形缓冲区溢写比例
     */
    private float[] MMRAAWithPerformanceStrategy(int sortmb, float spillper, Boolean isOptimizeSpiller, Boolean isUseTotalJVMHeapMemory) {
      float[] paras = new float[]{sortmb, spillper};
      Runtime runtime = Runtime.getRuntime();
      //返回虚拟机使用的最大堆内存量和可用堆内存
      long total = runtime.totalMemory() >> 20;
      long max = runtime.maxMemory() >> 20;
      long free = runtime.freeMemory() >> 20;
      long used = total - free; // 计算JVM当前使用的内存量
      float freeRatio;
      //TODO-PZH 使用total来计算可以有效避免 JVM Heap Memory 出现动态扩容的现象
      if (isUseTotalJVMHeapMemory) {
        freeRatio = 1 - (used * 1.0f / total);
      } else {
        freeRatio = 1 - (used * 1.0f / max);
      }
      // 获取配置的阈值默认为0.6（当空闲内存为百分之60以上的时候才增加环形缓冲区空间）
      final float threshold = job.getFloat(MRJobConfig.PZH_MR_PERFORMANCE_THRESHOLD, MRJobConfig.DEFAULT_PZH_MR_PERFORMANCE_THRESHOLD);
      LOG.info("[PZH-MEMO-INFO]:" + "total:" + total + "MB\tmax:" + max + "MB\tfree:" + free + "MB\tused:" + used + "MB\tfreeRatio:" + freeRatio + "\tthreshold:" + threshold);
      if (freeRatio > threshold) {
        long minimalSplitLength = job.getLong("mapreduce.minimalSplitLength", -1);
        assert minimalSplitLength>0;
        if (minimalSplitLength != mapTask.inputDataLength) {
          paras[0] = (int) (sortmb * (1.0f + freeRatio));
        } else {
          paras[0] = (long) (minimalSplitLength * (1.0f + freeRatio)) >> 20;
        }
        if (isOptimizeSpiller) {
          paras[1] = spillper + ((1.0f - spillper) * freeRatio * 0.5f);//TODO-PZH freeRatio*0.5这个0.5是将溢写比例增量控制的在50%，防止某些情况溢写比例接近1.0导致效率降低
        }
      }
      return paras;
    }

    @SuppressWarnings("unchecked")
    public void init(MapOutputCollector.Context context
    ) throws IOException, ClassNotFoundException {
      job = context.getJobConf();
      reporter = context.getReporter();
      mapTask = context.getMapTask();
      mapOutputFile = mapTask.getMapOutputFile();
      sortPhase = mapTask.getSortPhase();
      spilledRecordsCounter = reporter.getCounter(TaskCounter.SPILLED_RECORDS);
      partitions = job.getNumReduceTasks();
      rfs = ((LocalFileSystem) FileSystem.getLocal(job)).getRaw();

      //sanity checks
      float spillper =
              job.getFloat(JobContext.MAP_SORT_SPILL_PERCENT, (float) 0.8);
      int sortmb = job.getInt(MRJobConfig.IO_SORT_MB,
              MRJobConfig.DEFAULT_IO_SORT_MB);
      indexCacheMemoryLimit = job.getInt(JobContext.INDEX_CACHE_MEMORY_LIMIT,
              INDEX_CACHE_MEMORY_LIMIT_DEFAULT);
      LOG.info("[PZH-DEBUG]:" + "The current split that map will process length is " + mapTask.inputDataLength + "byte\t" + (mapTask.inputDataLength >> 20) + "MB]");
      // TODO-PZH 计算环形缓冲区容量和溢写百分比
      String roundBufferOptimalSchema = job.getTrimmed(MRJobConfig.PZH_MR_ROUND_BUFFER_OPTIMIZATION_STRATEGY);
      boolean isOptimalSpillper = job.getBoolean(MRJobConfig.PZH_MR_IS_OPTIMIZE_ROUND_BUFFER_SPILLPER, MRJobConfig.DEFAULT_PZH_MR_IS_OPTIMIZE_ROUND_BUFFER_SPILLPER);
      LOG.info("[PZH-DEBUG]:JobConf:mr.round.buffer.optimization.strategy=[" + roundBufferOptimalSchema + "]");
      LOG.info("[PZH-DEBUG]:JobConf:mr.is.optimize.round.buffer.spillper=[" + isOptimalSpillper + "]");
      float[] paras = null;
      if (roundBufferOptimalSchema != null) {
        if (MRJobConfig.PZH_MR_ROUND_BUFFER_OPTIMIZATION_STRATEGY_RATIO.equalsIgnoreCase(roundBufferOptimalSchema)) {
          paras = MMRAAWithProportionalStrategy(sortmb, spillper, isOptimalSpillper);
        } else if (MRJobConfig.PZH_MR_ROUND_BUFFER_OPTIMIZATION_STRATEGY_PERFORMANCE.equalsIgnoreCase(roundBufferOptimalSchema)) {
          boolean isUseTotalJVMHeapMemory = job.getBoolean(MRJobConfig.PZH_MR_IS_USE_TOTAL_JVM_HEAP_MEMORY, MRJobConfig.DEFAULT_PZH_MR_IS_USE_TOTAL_JVM_HEAP_MEMORY);
          LOG.info("[PZH-DEBUG]:JobConf:mr.is.use.total.jvm.heap.memory=[" + isUseTotalJVMHeapMemory + "]");
          paras = MMRAAWithPerformanceStrategy(sortmb, spillper, isOptimalSpillper, isUseTotalJVMHeapMemory);
        }
        if (paras != null) {
          sortmb = (int) paras[0];
          spillper = paras[1];
        }
      }
      LOG.info("[PZH-DEBUG]:" + "###[size:" + sortmb + ",spillPercent:" + spillper + "]###");

      if (spillper > (float) 1.0 || spillper <= (float) 0.0) {
        throw new IOException("Invalid \"" + JobContext.MAP_SORT_SPILL_PERCENT +
                "\": " + spillper);
      }
      if ((sortmb & 0x7FF) != sortmb) {
        throw new IOException(
                "Invalid \"" + JobContext.IO_SORT_MB + "\": " + sortmb);
      }
      sorter = ReflectionUtils.newInstance(job.getClass(
              MRJobConfig.MAP_SORT_CLASS, QuickSort.class,
              IndexedSorter.class), job);
      // buffers and accounting
      int maxMemUsage = sortmb << 20;
      maxMemUsage -= maxMemUsage % METASIZE;

      //PZH 使用一个新的变量存放环形缓冲区的长度，为了便于兼容堆外内存
      buflength = maxMemUsage;

      //TODO-PZH 开始创建环形缓冲区
      isUseDirectMemory = job.getBoolean(
              MRJobConfig.PZH_MR_DIRECT_MEMORY_ROUND_BUFFER_PARA_ENABLED,
              MRJobConfig.DEFAULT_PZH_MR_DIRECT_MEMORY_ROUND_BUFFER_PARA_ENABLED);
      LOG.info("[PZH-DEBUG]:mr.direct.memory.round.buffer.enabled=" + isUseDirectMemory);
      //TODO-PZH 创建直接内存环形缓冲区
      if (isUseDirectMemory) {
        kvDirectBuffer = ByteBuffer.allocateDirect(buflength);
        kvmeta = kvDirectBuffer.order(ByteOrder.nativeOrder()).asIntBuffer();
        lkvmeta = kvDirectBuffer.order(ByteOrder.nativeOrder()).asLongBuffer();
      } else {
        kvbuffer = new byte[buflength];
        //TODO-PZH 将以byte为单位的kvBuffer包装成4byte的IntBuffer,用来便于对kvmate(kv元数据)进行操作，kv一个元数据大小为（Value索引+分区索引+Key索引+Value长度）一共16byte
        kvmeta = ByteBuffer.wrap(kvbuffer)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        lkvmeta = ByteBuffer.wrap(kvbuffer)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer();
      }
      bufvoid = buflength;

      setEquator(0);
      bufstart = bufend = bufindex = equator;
      kvstart = kvend = kvindex;

      maxRec = kvmeta.capacity() / NMETA;
      softLimit = (int) (buflength * spillper);
      bufferRemaining = softLimit;
      LOG.info(JobContext.IO_SORT_MB + ": " + sortmb);
      LOG.info("soft limit at " + softLimit);
      LOG.info("bufstart = " + bufstart + "; bufvoid = " + bufvoid);
      LOG.info("kvstart = " + kvstart + "; length = " + maxRec);

      // k/v serialization
      comparator = job.getOutputKeyComparator();
      keyClass = (Class<K>) job.getMapOutputKeyClass();
      valClass = (Class<V>) job.getMapOutputValueClass();
      serializationFactory = new SerializationFactory(job);
      keySerializer = serializationFactory.getSerializer(keyClass);
      keySerializer.open(bb);
      valSerializer = serializationFactory.getSerializer(valClass);
      valSerializer.open(bb);

      // output counters
      mapOutputByteCounter = reporter.getCounter(TaskCounter.MAP_OUTPUT_BYTES);
      mapOutputRecordCounter =
              reporter.getCounter(TaskCounter.MAP_OUTPUT_RECORDS);
      fileOutputByteCounter = reporter
              .getCounter(TaskCounter.MAP_OUTPUT_MATERIALIZED_BYTES);

      // compression
      if (job.getCompressMapOutput()) {
        Class<? extends CompressionCodec> codecClass =
                job.getMapOutputCompressorClass(DefaultCodec.class);
        codec = ReflectionUtils.newInstance(codecClass, job);
      } else {
        codec = null;
      }

      // combiner
      final Counters.Counter combineInputCounter =
              reporter.getCounter(TaskCounter.COMBINE_INPUT_RECORDS);
      combinerRunner = CombinerRunner.create(job, getTaskID(),
              combineInputCounter,
              reporter, null);
      if (combinerRunner != null) {
        final Counters.Counter combineOutputCounter =
                reporter.getCounter(TaskCounter.COMBINE_OUTPUT_RECORDS);
        combineCollector = new CombineOutputCollector<K, V>(combineOutputCounter, reporter, job);
      } else {
        combineCollector = null;
      }
      spillInProgress = false;
      minSpillsForCombine = job.getInt(JobContext.MAP_COMBINE_MIN_SPILLS, 3);
      spillThread.setDaemon(true);
      spillThread.setName("SpillThread");
      spillLock.lock();
      try {
        spillThread.start();
        while (!spillThreadRunning) {
          spillDone.await();
        }
      } catch (InterruptedException e) {
        throw new IOException("Spill thread failed to initialize", e);
      } finally {
        spillLock.unlock();
      }
      if (sortSpillException != null) {
        throw new IOException("Spill thread failed to initialize",
                sortSpillException);
      }
    }

    /**
     * Serialize the key, value to intermediate storage.
     * When this method returns, kvindex must refer to sufficient unused
     * storage to store one METADATA.
     */
    public synchronized void collect(K key, V value, final int partition
    ) throws IOException {
      reporter.progress();
      if (key.getClass() != keyClass) {
        throw new IOException("Type mismatch in key from map: expected "
                + keyClass.getName() + ", received "
                + key.getClass().getName());
      }
      if (value.getClass() != valClass) {
        throw new IOException("Type mismatch in value from map: expected "
                + valClass.getName() + ", received "
                + value.getClass().getName());
      }
      if (partition < 0 || partition >= partitions) {
        throw new IOException("Illegal partition for " + key + " (" +
                partition + ")");
      }
      checkSpillException();
      bufferRemaining -= METASIZE;
      //TODO-PZH 检查剩余空间是否够写入一个元数据（元素据长度为16个字节）
      if (bufferRemaining <= 0) {
        //TODO-PZH 剩余空间不够，已经达到spiller的百分比了，开始溢写
        // start spill if the thread is not running and the soft limit has been
        // reached
        spillLock.lock();
        try {
          do {
            if (!spillInProgress) {
              final int kvbidx = 4 * kvindex;
              final int kvbend = 4 * kvend;
              // serialized, unspilled bytes always lie between kvindex and
              // bufindex, crossing the equator. Note that any void space
              // created by a reset must be included in "used" bytes
              final int bUsed = distanceTo(kvbidx, bufindex);
              final boolean bufsoftlimit = bUsed >= softLimit;
              if ((kvbend + METASIZE) % buflength !=
                      equator - (equator % METASIZE)) {
                // spill finished, reclaim space
                resetSpill();
                bufferRemaining = Math.min(
                        distanceTo(bufindex, kvbidx) - 2 * METASIZE,
                        softLimit - bUsed) - METASIZE;
                continue;
              } else if (bufsoftlimit && kvindex != kvend) {
                // spill records, if any collected; check latter, as it may
                // be possible for metadata alignment to hit spill pcnt
                startSpill();
                final int avgRec = (int)
                        (mapOutputByteCounter.getCounter() /
                                mapOutputRecordCounter.getCounter());
                // leave at least half the split buffer for serialization data
                // ensure that kvindex >= bufindex
                final int distkvi = distanceTo(bufindex, kvbidx);
                final int newPos = (bufindex +
                        Math.max(2 * METASIZE - 1,
                                Math.min(distkvi / 2,
                                        distkvi / (METASIZE + avgRec) * METASIZE)))
                        % buflength;
                setEquator(newPos);
                bufmark = bufindex = newPos;
                final int serBound = 4 * kvend;
                // bytes remaining before the lock must be held and limits
                // checked is the minimum of three arcs: the metadata space, the
                // serialization space, and the soft limit
                bufferRemaining = Math.min(
                        // metadata max
                        distanceTo(bufend, newPos),
                        Math.min(
                                // serialization max
                                distanceTo(newPos, serBound),
                                // soft limit
                                softLimit)) - 2 * METASIZE;
              }
            }
          } while (false);
        } finally {
          spillLock.unlock();
        }
      }

      try {
        // serialize key bytes into buffer
        int keystart = bufindex;
        //TODO-PZH 写入key
        keySerializer.serialize(key);
        if (bufindex < keystart) {
          //TODO-PZH 当key不连续的时候，调整key为连续的
          // wrapped the key; must make contiguous
          bb.shiftBufferedKey();
          keystart = 0;
        }
        // serialize value bytes into buffer
        final int valstart = bufindex;
        //TODO-PZH 写入value
        valSerializer.serialize(value);
        // It's possible for records to have zero length, i.e. the serializer
        // will perform no writes. To ensure that the boundary conditions are
        // checked and that the kvindex invariant is maintained, perform a
        // zero-length write into the buffer. The logic monitoring this could be
        // moved into collect, but this is cleaner and inexpensive. For now, it
        // is acceptable.
        bb.write(b0, 0, 0);

        // the record must be marked after the preceding write, as the metadata
        // for this record are not yet written
        int valend = bb.markRecord();

        mapOutputRecordCounter.increment(1);
        mapOutputByteCounter.increment(
                distanceTo(keystart, valend, bufvoid));

        // write accounting info
        kvmeta.put(kvindex + PARTITION, partition);
        kvmeta.put(kvindex + KEYSTART, keystart);
        kvmeta.put(kvindex + VALSTART, valstart);
        kvmeta.put(kvindex + VALLEN, distanceTo(valstart, valend));
        // advance kvindex
        kvindex = (kvindex - NMETA + kvmeta.capacity()) % kvmeta.capacity();
      } catch (MapBufferTooSmallException e) {
        LOG.info("Record too large for in-memory buffer: " + e.getMessage());
        spillSingleRecord(key, value, partition);
        mapOutputRecordCounter.increment(1);
        return;
      }
    }

    private TaskAttemptID getTaskID() {
      return mapTask.getTaskID();
    }

    /**
     * Set the point from which meta and serialization data expand. The meta
     * indices are aligned with the buffer, so metadata never spans the ends of
     * the circular buffer.
     */
    private void setEquator(int pos) {
      equator = pos;
      // set index prior to first entry, aligned at meta boundary
      final int aligned = pos - (pos % METASIZE);
      // Cast one of the operands to long to avoid integer overflow
      kvindex = (int)
              (((long) aligned - METASIZE + buflength) % buflength) / 4;
      LOG.info("(EQUATOR) " + pos + " kvi " + kvindex +
              "(" + (kvindex * 4) + ")");
    }

    /**
     * The spill is complete, so set the buffer and meta indices to be equal to
     * the new equator to free space for continuing collection. Note that when
     * kvindex == kvend == kvstart, the buffer is empty.
     */
    private void resetSpill() {
      final int e = equator;
      bufstart = bufend = e;
      final int aligned = e - (e % METASIZE);
      // set start/end to point to first meta record
      // Cast one of the operands to long to avoid integer overflow
      kvstart = kvend = (int)
              (((long) aligned - METASIZE + buflength) % buflength) / 4;
      LOG.info("(RESET) equator " + e + " kv " + kvstart + "(" +
              (kvstart * 4) + ")" + " kvi " + kvindex + "(" + (kvindex * 4) + ")");
    }

    /**
     * Compute the distance in bytes between two indices in the serialization
     * buffer.
     *
     * @see #distanceTo(int, int, int)
     */
    final int distanceTo(final int i, final int j) {
      return distanceTo(i, j, buflength);
    }

    /**
     * Compute the distance between two indices in the circular buffer given the
     * max distance.
     */
    int distanceTo(final int i, final int j, final int mod) {
      return i <= j
              ? j - i
              : mod - i + j;
    }

    /**
     * For the given meta position, return the offset into the int-sized
     * kvmeta buffer.
     */
    int offsetFor(int metapos) {
      return metapos * NMETA;
    }

    /**
     * Compare logical range, st i, j MOD offset capacity.
     * Compare by partition, then by key.
     *
     * @see IndexedSortable#compare
     */
    @Override
    public int compare(final int mi, final int mj) {
      final int kvi = offsetFor(mi % maxRec);
      final int kvj = offsetFor(mj % maxRec);
      final int kvip = kvmeta.get(kvi + PARTITION);
      final int kvjp = kvmeta.get(kvj + PARTITION);
      // sort by partition
      if (kvip != kvjp) {
        return kvip - kvjp;
      }
      // sort by key
      // TODO-PZH 比较两个key
      if (isUseDirectMemory) {
        return comparePlus(kvDirectBuffer,
                kvmeta.get(kvi + KEYSTART), // TODO-PZH 获取key开始的下标
                kvmeta.get(kvi + VALSTART) - kvmeta.get(kvi + KEYSTART), //TODO-PZH 获取key的长度
                kvDirectBuffer,
                kvmeta.get(kvj + KEYSTART),
                kvmeta.get(kvj + VALSTART) - kvmeta.get(kvj + KEYSTART));
      } else {
        return comparator.compare(kvbuffer,
                kvmeta.get(kvi + KEYSTART), // TODO-PZH 获取key开始的下标
                kvmeta.get(kvi + VALSTART) - kvmeta.get(kvi + KEYSTART), //TODO-PZH 获取key的长度
                kvbuffer,
                kvmeta.get(kvj + KEYSTART),
                kvmeta.get(kvj + VALSTART) - kvmeta.get(kvj + KEYSTART));
      }
    }

    //TODO-PZH ByteBuffer进行比较
    public int comparePlus(ByteBuffer b1, int s1, int l1,
                           ByteBuffer b2, int s2, int l2) {
      int n1 = WritableUtils.decodeVIntSize(b1.get(s1));
      int n2 = WritableUtils.decodeVIntSize(b2.get(s2));
      return compareToPlus(b1, s1 + n1, l1 - n1, b2, s2 + n2, l2 - n2);
    }

    public int compareToPlus(ByteBuffer buffer1, int offset1, int length1,
                             ByteBuffer buffer2, int offset2, int length2) {
      // Short circuit equal case
      if (buffer1 == buffer2 &&
              offset1 == offset2 &&
              length1 == length2) {
        return 0;
      }
      int minLength = Math.min(length1, length2);
      int minWords = minLength / Longs.BYTES;

      for (int i = 0; i < minWords * Longs.BYTES; i += Longs.BYTES) {
        long lw = buffer1.getLong(offset1 + i);
        long rw = buffer2.getLong(offset2 + i);
        long diff = lw ^ rw;

        if (diff != 0) {
          if (!ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            return (lw + Long.MIN_VALUE) < (rw + Long.MIN_VALUE) ? -1 : 1;
          }

          // Use binary search
          int n = 0;
          int y;
          int x = (int) diff;
          if (x == 0) {
            x = (int) (diff >>> 32);
            n = 32;
          }

          y = x << 16;
          if (y == 0) {
            n += 16;
          } else {
            x = y;
          }

          y = x << 8;
          if (y == 0) {
            n += 8;
          }
          return (int) (((lw >>> n) & 0xFFL) - ((rw >>> n) & 0xFFL));
        }
      }

      // The epilogue to cover the last (minLength % 8) elements.
      for (int i = minWords * Longs.BYTES; i < minLength; i++) {
        int result = UnsignedBytes.compare(
                buffer1.get(offset1 + i),
                buffer1.get(offset2 + i));
        if (result != 0) {
          return result;
        }
      }
      return length1 - length2;
    }
    //TODO-PZH ByteBuffer进行比较


    final byte META_BUFFER_TMP[] = new byte[METASIZE];

    /**
     * Swap metadata for items i, j
     *
     * @see IndexedSortable#swap
     */
    @Override
    public void swap(final int mi, final int mj) {
      if (isUseDirectMemory) {
        int iOff = (mi % maxRec) * 2;
        int jOff = (mj % maxRec) * 2;
        Long tempL;
        for (int j = 0; j < 2; j++) {
          tempL = lkvmeta.get(jOff + j);
          lkvmeta.put(jOff + j, lkvmeta.get(iOff + j));
          lkvmeta.put(iOff + j, tempL);
        }
      } else {
        int iOff = (mi % maxRec) * METASIZE;
        int jOff = (mj % maxRec) * METASIZE;
        System.arraycopy(kvbuffer, iOff, META_BUFFER_TMP, 0, METASIZE);
        System.arraycopy(kvbuffer, jOff, kvbuffer, iOff, METASIZE);
        System.arraycopy(META_BUFFER_TMP, 0, kvbuffer, jOff, METASIZE);
      }
    }

    /**
     * Inner class managing the spill of serialized records to disk.
     */
    protected class BlockingBuffer extends DataOutputStream {

      public BlockingBuffer() {
        super(new Buffer());
      }

      /**
       * Mark end of record. Note that this is required if the buffer is to
       * cut the spill in the proper place.
       */
      public int markRecord() {
        bufmark = bufindex;
        return bufindex;
      }

      /**
       * Set position from last mark to end of writable buffer, then rewrite
       * the data between last mark and kvindex.
       * This handles a special case where the key wraps around the buffer.
       * If the key is to be passed to a RawComparator, then it must be
       * contiguous in the buffer. This recopies the data in the buffer back
       * into itself, but starting at the beginning of the buffer. Note that
       * this method should <b>only</b> be called immediately after detecting
       * this condition. To call it at any other time is undefined and would
       * likely result in data loss or corruption.
       *
       * @see #markRecord()
       */
      protected void shiftBufferedKey() throws IOException {
        // spillLock unnecessary; both kvend and kvindex are current
        int headbytelen = bufvoid - bufmark;
        bufvoid = bufmark;
        final int kvbidx = 4 * kvindex;
        final int kvbend = 4 * kvend;
        final int avail =
                Math.min(distanceTo(0, kvbidx), distanceTo(0, kvbend));
        if (bufindex + headbytelen < avail) {
          if (isUseDirectMemory) {
            for (int i = 0; i < bufindex; i++) {
              kvDirectBuffer.put(headbytelen + i, kvDirectBuffer.get(i));
            }
            for (int i = 0; i < headbytelen; i++) {
              kvDirectBuffer.put(i, kvDirectBuffer.get(bufvoid + i));
            }
          } else {
            // TODO-PZH 先将拷贝key的下半部分，再拷贝上半部分
            System.arraycopy(kvbuffer, 0, kvbuffer, headbytelen, bufindex);
            System.arraycopy(kvbuffer, bufvoid, kvbuffer, 0, headbytelen);
          }
          bufindex += headbytelen;
          bufferRemaining -= buflength - bufvoid;
        } else {
          byte[] keytmp = new byte[bufindex];
          if (isUseDirectMemory) {
            for (int i = 0; i < bufindex; i++) {
              keytmp[i] = kvDirectBuffer.get(i);
            }
            bufindex = 0;
            byte[] keyHeadTmp = new byte[headbytelen];
            for (int i = 0; i < headbytelen; i++) {
              keyHeadTmp[i] = kvDirectBuffer.get(bufmark + i);
            }
            out.write(keyHeadTmp);
          } else {
            System.arraycopy(kvbuffer, 0, keytmp, 0, bufindex);
            bufindex = 0;
            out.write(kvbuffer, bufmark, headbytelen);
          }
          out.write(keytmp);
        }
      }
    }

    public class Buffer extends OutputStream {
      private final byte[] scratch = new byte[1];

      @Override
      public void write(int v)
              throws IOException {
        scratch[0] = (byte) v;
        write(scratch, 0, 1);
      }

      /**
       * Attempt to write a sequence of bytes to the collection buffer.
       * This method will block if the spill thread is running and it
       * cannot write.
       *
       * @throws MapBufferTooSmallException if record is too large to
       *                                    deserialize into the collection buffer.
       */
      @Override
      public void write(byte b[], int off, int len)
              throws IOException {
        // must always verify the invariant that at least METASIZE bytes are
        // available beyond kvindex, even when len == 0
        bufferRemaining -= len;
        if (bufferRemaining <= 0) {
          // writing these bytes could exhaust available buffer space or fill
          // the buffer to soft limit. check if spill or blocking are necessary
          boolean blockwrite = false;
          spillLock.lock();
          try {
            do {
              checkSpillException();

              final int kvbidx = 4 * kvindex;
              final int kvbend = 4 * kvend;
              // ser distance to key index
              final int distkvi = distanceTo(bufindex, kvbidx);
              // ser distance to spill end index
              final int distkve = distanceTo(bufindex, kvbend);

              // if kvindex is closer than kvend, then a spill is neither in
              // progress nor complete and reset since the lock was held. The
              // write should block only if there is insufficient space to
              // complete the current write, write the metadata for this record,
              // and write the metadata for the next record. If kvend is closer,
              // then the write should block if there is too little space for
              // either the metadata or the current write. Note that collect
              // ensures its metadata requirement with a zero-length write
              blockwrite = distkvi <= distkve
                      ? distkvi <= len + 2 * METASIZE
                      : distkve <= len || distanceTo(bufend, kvbidx) < 2 * METASIZE;

              if (!spillInProgress) {
                if (blockwrite) {
                  if ((kvbend + METASIZE) % buflength !=
                          equator - (equator % METASIZE)) {
                    // spill finished, reclaim space
                    // need to use meta exclusively; zero-len rec & 100% spill
                    // pcnt would fail
                    resetSpill(); // resetSpill doesn't move bufindex, kvindex
                    bufferRemaining = Math.min(
                            distkvi - 2 * METASIZE,
                            softLimit - distanceTo(kvbidx, bufindex)) - len;
                    continue;
                  }
                  // we have records we can spill; only spill if blocked
                  if (kvindex != kvend) {
                    startSpill();
                    // Blocked on this write, waiting for the spill just
                    // initiated to finish. Instead of repositioning the marker
                    // and copying the partial record, we set the record start
                    // to be the new equator
                    setEquator(bufmark);
                  } else {
                    // We have no buffered records, and this record is too large
                    // to write into kvbuffer. We must spill it directly from
                    // collect
                    final int size = distanceTo(bufstart, bufindex) + len;
                    setEquator(0);
                    bufstart = bufend = bufindex = equator;
                    kvstart = kvend = kvindex;
                    bufvoid = buflength;
                    throw new MapBufferTooSmallException(size + " bytes");
                  }
                }
              }

              if (blockwrite) {
                // wait for spill
                try {
                  while (spillInProgress) {
                    reporter.progress();
                    spillDone.await();
                  }
                } catch (InterruptedException e) {
                  throw new IOException(
                          "Buffer interrupted while waiting for the writer", e);
                }
              }
            } while (blockwrite);
          } finally {
            spillLock.unlock();
          }
        }
        // here, we know that we have sufficient space to write
        if (bufindex + len > bufvoid) {
          final int gaplen = bufvoid - bufindex;
          if (isUseDirectMemory) {
            for (int i = 0; i < gaplen; i++)
              kvDirectBuffer.put(bufindex + i, b[i]);
          } else {
            System.arraycopy(b, off, kvbuffer, bufindex, gaplen);
          }
          len -= gaplen;
          off += gaplen;
          bufindex = 0;
        }
        if (isUseDirectMemory) {
          for (int i = 0; i < len; i++)
            kvDirectBuffer.put(bufindex + i, b[off + i]);
        } else {
          System.arraycopy(b, off, kvbuffer, bufindex, len);
        }
        bufindex += len;
      }
    }

    public void flush() throws IOException, ClassNotFoundException,
            InterruptedException {
      LOG.info("Starting flush of map output");
      if (isUseDirectMemory) {
        if (kvDirectBuffer == null) {
          LOG.info("kvDirectBuffer is null. Skipping flush.");
          return;
        }
      } else {
        if (kvbuffer == null) {
          LOG.info("kvbuffer is null. Skipping flush.");
          return;
        }
      }
      spillLock.lock();
      try {
        while (spillInProgress) {
          reporter.progress();
          spillDone.await();
        }
        checkSpillException();

        final int kvbend = 4 * kvend;
        if ((kvbend + METASIZE) % buflength !=
                equator - (equator % METASIZE)) {
          // spill finished
          resetSpill();
        }
        if (kvindex != kvend) {
          kvend = (kvindex + NMETA) % kvmeta.capacity();
          bufend = bufmark;
          LOG.info("Spilling map output");
          LOG.info("bufstart = " + bufstart + "; bufend = " + bufmark +
                  "; bufvoid = " + bufvoid);
          LOG.info("kvstart = " + kvstart + "(" + (kvstart * 4) +
                  "); kvend = " + kvend + "(" + (kvend * 4) +
                  "); length = " + (distanceTo(kvend, kvstart,
                  kvmeta.capacity()) + 1) + "/" + maxRec);
          long start = System.currentTimeMillis();
          sortAndSpill();
          long end = System.currentTimeMillis();
          mapTask.sortAndSpillElapseTimeList.add(end - start);
        }
      } catch (InterruptedException e) {
        throw new IOException("Interrupted while waiting for the writer", e);
      } finally {
        spillLock.unlock();
      }
      assert !spillLock.isHeldByCurrentThread();
      // shut down spill thread and wait for it to exit. Since the preceding
      // ensures that it is finished with its work (and sortAndSpill did not
      // throw), we elect to use an interrupt instead of setting a flag.
      // Spilling simultaneously from this thread while the spill thread
      // finishes its work might be both a useful way to extend this and also
      // sufficient motivation for the latter approach.
      try {
        spillThread.interrupt();
        spillThread.join();
      } catch (InterruptedException e) {
        throw new IOException("Spill failed", e);
      }
      // release sort buffer before the merge
      if (isUseDirectMemory) {
        kvDirectBuffer = null;
      } else {
        kvbuffer = null;
      }
      //TODO-PZH 合并所有溢写的文件
      mergeParts();
      Path outputPath = mapOutputFile.getOutputFile();
      fileOutputByteCounter.increment(rfs.getFileStatus(outputPath).getLen());
      // If necessary, make outputs permissive enough for shuffling.
      if (!SHUFFLE_OUTPUT_PERM.equals(
              SHUFFLE_OUTPUT_PERM.applyUMask(FsPermission.getUMask(job)))) {
        Path indexPath = mapOutputFile.getOutputIndexFile();
        rfs.setPermission(outputPath, SHUFFLE_OUTPUT_PERM);
        rfs.setPermission(indexPath, SHUFFLE_OUTPUT_PERM);
      }
    }

    public void close() {
    }

    protected class SpillThread extends Thread {

      @Override
      public void run() {
        spillLock.lock();
        spillThreadRunning = true;
        try {
          while (true) {
            spillDone.signal();
            while (!spillInProgress) {
              spillReady.await();
            }
            try {
              spillLock.unlock();
              long start = System.currentTimeMillis();
              sortAndSpill();
              long end = System.currentTimeMillis();
              mapTask.sortAndSpillElapseTimeList.add(end - start);
            } catch (Throwable t) {
              sortSpillException = t;
            } finally {
              spillLock.lock();
              if (bufend < bufstart) {
                bufvoid = buflength;
              }
              kvstart = kvend;
              bufstart = bufend;
              spillInProgress = false;
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          spillLock.unlock();
          spillThreadRunning = false;
        }
      }
    }

    private void checkSpillException() throws IOException {
      final Throwable lspillException = sortSpillException;
      if (lspillException != null) {
        if (lspillException instanceof Error) {
          final String logMsg = "Task " + getTaskID() + " failed : " +
                  StringUtils.stringifyException(lspillException);
          mapTask.reportFatalError(getTaskID(), lspillException, logMsg,
                  false);
        }
        throw new IOException("Spill failed", lspillException);
      }
    }

    private void startSpill() {
      assert !spillInProgress;
      kvend = (kvindex + NMETA) % kvmeta.capacity();
      bufend = bufmark;
      spillInProgress = true;
      LOG.info("Spilling map output");
      LOG.info("bufstart = " + bufstart + "; bufend = " + bufmark +
              "; bufvoid = " + bufvoid);
      LOG.info("kvstart = " + kvstart + "(" + (kvstart * 4) +
              "); kvend = " + kvend + "(" + (kvend * 4) +
              "); length = " + (distanceTo(kvend, kvstart,
              kvmeta.capacity()) + 1) + "/" + maxRec);
      spillReady.signal();
    }

    private void sortAndSpill() throws IOException, ClassNotFoundException,
            InterruptedException {
      //approximate the length of the output file to be the length of the
      //buffer + header lengths for the partitions
      final long size = distanceTo(bufstart, bufend, bufvoid) +
              partitions * APPROX_HEADER_LENGTH;
      FSDataOutputStream out = null;
      FSDataOutputStream partitionOut = null;
      try {
        //TODO-PZH 用来存放每次溢写文件（spill.out）中每个partition数据段的索引。
        // create spill file
        final SpillRecord spillRec = new SpillRecord(partitions);
        final Path filename =
                mapOutputFile.getSpillFileForWrite(numSpills, size);
        out = rfs.create(filename);

        final int mstart = kvend / NMETA;
        final int mend = 1 + // kvend is a valid record
                (kvstart >= kvend
                        ? kvstart
                        : kvmeta.capacity() + kvstart) / NMETA;

        //TODO-PZH 记录每次溢写时的排序时间
        long start = System.currentTimeMillis();
        sorter.sort(MapOutputBuffer.this, mstart, mend, reporter);
        long end = System.currentTimeMillis();
        mapTask.sortElapseTimeList.add(end - start);
        //TODO-PZH 记录每次溢写时的排序时间

        int spindex = mstart;
        final IndexRecord rec = new IndexRecord();
        InMemValBytes value = null;
        DataInputBuffer key = null;
        if (!isUseDirectMemory) {
          value = new InMemValBytes();
          key = new DataInputBuffer();
        }
        for (int i = 0; i < partitions; ++i) {
          IFile.Writer<K, V> writer = null;
          try {
            long segmentStart = out.getPos();
            partitionOut = CryptoUtils.wrapIfNecessary(job, out, false);
            writer = new Writer<K, V>(job, partitionOut, keyClass, valClass, codec,
                    spilledRecordsCounter);
            if (combinerRunner == null) {
              // spill directly
              while (spindex < mend && kvmeta.get(offsetFor(spindex % maxRec) + PARTITION) == i) {
                final int kvoff = offsetFor(spindex % maxRec);
                int keystart = kvmeta.get(kvoff + KEYSTART);
                int valstart = kvmeta.get(kvoff + VALSTART);
                if (isUseDirectMemory) {
                  writer.append(kvDirectBuffer, keystart, valstart - keystart, valstart, kvmeta.get(kvoff + VALLEN), bufvoid);
                } else {
                  key.reset(kvbuffer, keystart, valstart - keystart);
                  getVBytesForOffset(kvoff, value);
                  writer.append(key, value);
                }
                ++spindex;
              }
            } else {
              int spstart = spindex;
              while (spindex < mend &&
                      kvmeta.get(offsetFor(spindex % maxRec)
                              + PARTITION) == i) {
                ++spindex;
              }
              // Note: we would like to avoid the combiner if we've fewer
              // than some threshold of records for a partition
              if (spstart != spindex) {
                combineCollector.setWriter(writer);
                RawKeyValueIterator kvIter =
                        new MRResultIterator(spstart, spindex);
                combinerRunner.combine(kvIter, combineCollector);
              }
            }

            // close the writer
            writer.close();
            if (partitionOut != out) {
              partitionOut.close();
              partitionOut = null;
            }

            // record offsets
            rec.startOffset = segmentStart;
            rec.rawLength = writer.getRawLength() + CryptoUtils.cryptoPadding(job);
            rec.partLength = writer.getCompressedLength() + CryptoUtils.cryptoPadding(job);
            spillRec.putIndex(rec, i);

            writer = null;
          } finally {
            if (null != writer) writer.close();
          }
        }

        //TODO-PZH 判断当前内存中存放spill.out索引的缓存是否达到预设的值（1mb）
        if (totalIndexCacheMemory >= indexCacheMemoryLimit) {
          //TODO-PZH 如果索引缓存达到了预设值则将后续所有的spill.out的索引值写入到磁盘中
          // create spill index file
          Path indexFilename =
                  mapOutputFile.getSpillIndexFileForWrite(numSpills, partitions
                          * MAP_OUTPUT_INDEX_RECORD_LENGTH);
          spillRec.writeToFile(indexFilename, job);
        } else {
          //TODO-PZH 如果索引缓存没有达到预设值则将本次索引记录添加到indexCacheList中，然后累加当前已经缓存了的索引长度
          indexCacheList.add(spillRec);
          totalIndexCacheMemory +=
                  spillRec.size() * MAP_OUTPUT_INDEX_RECORD_LENGTH;
        }
        LOG.info("Finished spill " + numSpills);
        ++numSpills;
      } finally {
        if (out != null) out.close();
        if (partitionOut != null) {
          partitionOut.close();
        }
      }
    }

    /**
     * Handles the degenerate case where serialization fails to fit in
     * the in-memory buffer, so we must spill the record from collect
     * directly to a spill file. Consider this "losing".
     */
    private void spillSingleRecord(final K key, final V value,
                                   int partition) throws IOException {
      long size = buflength + partitions * APPROX_HEADER_LENGTH;
      FSDataOutputStream out = null;
      FSDataOutputStream partitionOut = null;
      try {
        // create spill file
        final SpillRecord spillRec = new SpillRecord(partitions);
        final Path filename =
                mapOutputFile.getSpillFileForWrite(numSpills, size);
        out = rfs.create(filename);

        // we don't run the combiner for a single record
        IndexRecord rec = new IndexRecord();
        for (int i = 0; i < partitions; ++i) {
          IFile.Writer<K, V> writer = null;
          try {
            long segmentStart = out.getPos();
            // Create a new codec, don't care!
            partitionOut = CryptoUtils.wrapIfNecessary(job, out, false);
            writer = new IFile.Writer<K, V>(job, partitionOut, keyClass, valClass, codec,
                    spilledRecordsCounter);

            if (i == partition) {
              final long recordStart = out.getPos();
              writer.append(key, value);
              // Note that our map byte count will not be accurate with
              // compression
              mapOutputByteCounter.increment(out.getPos() - recordStart);
            }
            writer.close();
            if (partitionOut != out) {
              partitionOut.close();
              partitionOut = null;
            }

            // record offsets
            rec.startOffset = segmentStart;
            rec.rawLength = writer.getRawLength() + CryptoUtils.cryptoPadding(job);
            rec.partLength = writer.getCompressedLength() + CryptoUtils.cryptoPadding(job);
            spillRec.putIndex(rec, i);

            writer = null;
          } catch (IOException e) {
            if (null != writer) writer.close();
            throw e;
          }
        }
        if (totalIndexCacheMemory >= indexCacheMemoryLimit) {
          // create spill index file
          Path indexFilename =
                  mapOutputFile.getSpillIndexFileForWrite(numSpills, partitions
                          * MAP_OUTPUT_INDEX_RECORD_LENGTH);
          spillRec.writeToFile(indexFilename, job);
        } else {
          indexCacheList.add(spillRec);
          totalIndexCacheMemory +=
                  spillRec.size() * MAP_OUTPUT_INDEX_RECORD_LENGTH;
        }
        ++numSpills;
      } finally {
        if (out != null) out.close();
        if (partitionOut != null) {
          partitionOut.close();
        }
      }
    }

    /**
     * Given an offset, populate vbytes with the associated set of
     * deserialized value bytes. Should only be called during a spill.
     */
    private void getVBytesForOffset(int kvoff, InMemValBytes vbytes) {
      // get the keystart for the next serialized value to be the end
      // of this value. If this is the last value in the buffer, use bufend
      final int vallen = kvmeta.get(kvoff + VALLEN);
      assert vallen >= 0;
      int offset = kvmeta.get(kvoff + VALSTART);
      if (isUseDirectMemory) {
        byte[] buf = new byte[vallen];
        if (offset + vallen > bufvoid) {
          final int taillen = bufvoid - offset;
          for (int i = 0; i < taillen; i++) {
            buf[i] = kvDirectBuffer.get(offset + i);
          }
          for (int i = 0; i < vallen - taillen; i++) {
            buf[taillen + i] = kvDirectBuffer.get(i);
          }
        } else {
          for (int i = 0; i < vallen; i++) {
            buf[i] = kvDirectBuffer.get(offset + i);
          }
        }
        vbytes.reset(buf, 0, vallen);
      } else {
        vbytes.reset(kvbuffer, offset, vallen);
      }
    }

    /**
     * Inner class wrapping valuebytes, used for appendRaw.
     */
    protected class InMemValBytes extends DataInputBuffer {
      private byte[] buffer;
      private int start;
      private int length;

      public void reset(byte[] buffer, int start, int length) {
        this.buffer = buffer;
        this.start = start;
        this.length = length;

        if (start + length > bufvoid) {
          this.buffer = new byte[this.length];
          final int taillen = bufvoid - start;
          System.arraycopy(buffer, start, this.buffer, 0, taillen);
          System.arraycopy(buffer, 0, this.buffer, taillen, length - taillen);
          this.start = 0;
        }

        super.reset(this.buffer, this.start, this.length);
      }
    }

    protected class MRResultIterator implements RawKeyValueIterator {
      private final DataInputBuffer keybuf = new DataInputBuffer();
      private final InMemValBytes vbytes = new InMemValBytes();
      private final int end;
      private int current;

      public MRResultIterator(int start, int end) {
        this.end = end;
        current = start - 1;
      }

      public boolean next() throws IOException {
        return ++current < end;
      }

      public DataInputBuffer getKey() throws IOException {
        final int kvoff = offsetFor(current % maxRec);
        int offset = kvmeta.get(kvoff + KEYSTART);
        int len = kvmeta.get(kvoff + VALSTART) - offset;
        if (isUseDirectMemory) {
          byte[] buf = new byte[len];
          for (int i = 0; i < len; i++) {
            buf[i] = kvDirectBuffer.get(offset + i);
          }
          keybuf.reset(buf, 0, len);
        } else {
          keybuf.reset(kvbuffer, offset, len);
        }
        return keybuf;
      }

      public DataInputBuffer getValue() throws IOException {
        getVBytesForOffset(offsetFor(current % maxRec), vbytes);
        return vbytes;
      }

      public Progress getProgress() {
        return null;
      }

      public void close() {
      }
    }

    private void mergeParts() throws IOException, InterruptedException,
            ClassNotFoundException {
      // get the approximate size of the final output/index files
      long finalOutFileSize = 0;
      long finalIndexFileSize = 0;
      final Path[] filename = new Path[numSpills];
      final TaskAttemptID mapId = getTaskID();

      //TODO-PZH 将磁盘中的spill.out文件path读取到一个Path数组中
      for (int i = 0; i < numSpills; i++) {
        filename[i] = mapOutputFile.getSpillFile(i);
        finalOutFileSize += rfs.getFileStatus(filename[i]).getLen();
      }
      if (numSpills == 1) { //the spill is the final output
        sameVolRename(filename[0],
                mapOutputFile.getOutputFileForWriteInVolume(filename[0]));
        if (indexCacheList.size() == 0) {
          sameVolRename(mapOutputFile.getSpillIndexFile(0),
                  mapOutputFile.getOutputIndexFileForWriteInVolume(filename[0]));
        } else {
          indexCacheList.get(0).writeToFile(
                  mapOutputFile.getOutputIndexFileForWriteInVolume(filename[0]), job);
        }
        sortPhase.complete();
        return;
      }

      // read in paged indices
      //TODO-PZH 将磁盘中的spill.out文件索引信息文件读取出来
      for (int i = indexCacheList.size(); i < numSpills; ++i) {
        Path indexFileName = mapOutputFile.getSpillIndexFile(i);
        indexCacheList.add(new SpillRecord(indexFileName, job));
      }

      //make correction in the length to include the sequence file header
      //lengths for each partition
      finalOutFileSize += partitions * APPROX_HEADER_LENGTH;
      finalIndexFileSize = partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH;
      Path finalOutputFile =
              mapOutputFile.getOutputFileForWrite(finalOutFileSize);
      Path finalIndexFile =
              mapOutputFile.getOutputIndexFileForWrite(finalIndexFileSize);

      //The output stream for the final single output file
      FSDataOutputStream finalOut = rfs.create(finalOutputFile, true, 4096);
      FSDataOutputStream finalPartitionOut = null;

      if (numSpills == 0) {
        //create dummy files
        IndexRecord rec = new IndexRecord();
        SpillRecord sr = new SpillRecord(partitions);
        try {
          for (int i = 0; i < partitions; i++) {
            long segmentStart = finalOut.getPos();
            finalPartitionOut = CryptoUtils.wrapIfNecessary(job, finalOut,
                    false);
            Writer<K, V> writer =
                    new Writer<K, V>(job, finalPartitionOut, keyClass, valClass, codec, null);
            writer.close();
            if (finalPartitionOut != finalOut) {
              finalPartitionOut.close();
              finalPartitionOut = null;
            }
            rec.startOffset = segmentStart;
            rec.rawLength = writer.getRawLength() + CryptoUtils.cryptoPadding(job);
            rec.partLength = writer.getCompressedLength() + CryptoUtils.cryptoPadding(job);
            sr.putIndex(rec, i);
          }
          sr.writeToFile(finalIndexFile, job);
        } finally {
          finalOut.close();
          if (finalPartitionOut != null) {
            finalPartitionOut.close();
          }
        }
        sortPhase.complete();
        return;
      }
      {
        sortPhase.addPhases(partitions); // Divide sort phase into sub-phases

        IndexRecord rec = new IndexRecord();
        final SpillRecord spillRec = new SpillRecord(partitions);
        //TODO-PZH 先按照分区进行合并
        for (int parts = 0; parts < partitions; parts++) {
          //create the segments to be merged
          List<Segment<K, V>> segmentList =
                  new ArrayList<Segment<K, V>>(numSpills);
          //TODO-PZH 当前分区数据spill.out里面的的起始位置和长度记录在Segment中并添加到segmentList中
          for (int i = 0; i < numSpills; i++) {
            IndexRecord indexRecord = indexCacheList.get(i).getIndex(parts);

            Segment<K, V> s =
                    new Segment<K, V>(job, rfs, filename[i], indexRecord.startOffset,
                            indexRecord.partLength, codec, true);
            segmentList.add(i, s);

            if (LOG.isDebugEnabled()) {
              LOG.debug("MapId=" + mapId + " Reducer=" + parts +
                      "Spill =" + i + "(" + indexRecord.startOffset + "," +
                      indexRecord.rawLength + ", " + indexRecord.partLength + ")");
            }
          }

          int mergeFactor = job.getInt(MRJobConfig.IO_SORT_FACTOR,
                  MRJobConfig.DEFAULT_IO_SORT_FACTOR);
          // sort the segments only if there are intermediate merges
          boolean sortSegments = segmentList.size() > mergeFactor;
          //merge
          @SuppressWarnings("unchecked")
          RawKeyValueIterator kvIter = Merger.merge(job, rfs,
                  keyClass, valClass, codec,
                  segmentList, mergeFactor,
                  new Path(mapId.toString()),
                  job.getOutputKeyComparator(), reporter, sortSegments,
                  null, spilledRecordsCounter, sortPhase.phase(),
                  TaskType.MAP);

          //write merged output to disk
          long segmentStart = finalOut.getPos();
          finalPartitionOut = CryptoUtils.wrapIfNecessary(job, finalOut, false);
          Writer<K, V> writer =
                  new Writer<K, V>(job, finalPartitionOut, keyClass, valClass, codec,
                          spilledRecordsCounter);
          //TODO-PZH 判断是否设置的Combiner且这个Mapper的溢写次数是否超过了minSpillsForCombine数值
          //如果超过了minSpillsForCombine数值则在merge的时候启用Combiner
          if (combinerRunner == null || numSpills < minSpillsForCombine) {
            Merger.writeFile(kvIter, writer, reporter, job);
          } else {
            combineCollector.setWriter(writer);
            combinerRunner.combine(kvIter, combineCollector);
          }

          //close
          writer.close();
          if (finalPartitionOut != finalOut) {
            finalPartitionOut.close();
            finalPartitionOut = null;
          }

          sortPhase.startNextPhase();

          // record offsets
          rec.startOffset = segmentStart;
          rec.rawLength = writer.getRawLength() + CryptoUtils.cryptoPadding(job);
          rec.partLength = writer.getCompressedLength() + CryptoUtils.cryptoPadding(job);
          spillRec.putIndex(rec, parts);
        }
        spillRec.writeToFile(finalIndexFile, job);
        finalOut.close();
        if (finalPartitionOut != null) {
          finalPartitionOut.close();
        }
        for (int i = 0; i < numSpills; i++) {
          rfs.delete(filename[i], true);
        }
      }
    }

    /**
     * Rename srcPath to dstPath on the same volume. This is the same
     * as RawLocalFileSystem's rename method, except that it will not
     * fall back to a copy, and it will create the target directory
     * if it doesn't exist.
     */
    private void sameVolRename(Path srcPath,
                               Path dstPath) throws IOException {
      RawLocalFileSystem rfs = (RawLocalFileSystem) this.rfs;
      File src = rfs.pathToFile(srcPath);
      File dst = rfs.pathToFile(dstPath);
      if (!dst.getParentFile().exists()) {
        if (!dst.getParentFile().mkdirs()) {
          throw new IOException("Unable to rename " + src + " to "
                  + dst + ": couldn't create parent directory");
        }
      }

      if (!src.renameTo(dst)) {
        throw new IOException("Unable to rename " + src + " to " + dst);
      }
    }
  } // MapOutputBuffer

  /**
   * Exception indicating that the allocated sort buffer is insufficient
   * to hold the current record.
   */
  @SuppressWarnings("serial")
  private static class MapBufferTooSmallException extends IOException {
    public MapBufferTooSmallException(String s) {
      super(s);
    }
  }

  private <INKEY, INVALUE, OUTKEY, OUTVALUE>
  void closeQuietly(RecordReader<INKEY, INVALUE> c) {
    if (c != null) {
      try {
        c.close();
      } catch (IOException ie) {
        // Ignore
        LOG.info("Ignoring exception during close for " + c, ie);
      }
    }
  }

  private <OUTKEY, OUTVALUE>
  void closeQuietly(MapOutputCollector<OUTKEY, OUTVALUE> c) {
    if (c != null) {
      try {
        c.close();
      } catch (Exception ie) {
        // Ignore
        LOG.info("Ignoring exception during close for " + c, ie);
      }
    }
  }

  private <INKEY, INVALUE, OUTKEY, OUTVALUE>
  void closeQuietly(
          org.apache.hadoop.mapreduce.RecordReader<INKEY, INVALUE> c) {
    if (c != null) {
      try {
        c.close();
      } catch (Exception ie) {
        // Ignore
        LOG.info("Ignoring exception during close for " + c, ie);
      }
    }
  }

  private <INKEY, INVALUE, OUTKEY, OUTVALUE>
  void closeQuietly(
          org.apache.hadoop.mapreduce.RecordWriter<OUTKEY, OUTVALUE> c,
          org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE>.Context
                  mapperContext) {
    if (c != null) {
      try {
        c.close(mapperContext);
      } catch (Exception ie) {
        // Ignore
        LOG.info("Ignoring exception during close for " + c, ie);
      }
    }
  }
}
