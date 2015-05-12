/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.tango.cassandra.bench;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.db.compaction.LeveledCompactionStrategy;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.io.compress.CompressionParameters;
import org.apache.cassandra.io.compress.SnappyCompressor;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.locator.SimpleStrategy;
import org.apache.cassandra.service.MigrationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

public class SchemaLoader {
  private static Logger logger = LoggerFactory.getLogger(SchemaLoader.class);


  public static void loadSchema() throws ConfigurationException {
    prepareServer();

    // Migrations aren't happy if gossiper is not started.  Even if we don't use migrations though,
    // some tests now expect us to start gossip for them.
    startGossiper();

    // if you're messing with low-level sstable stuff, it can be useful to inject the schema directly
//        Schema.instance.load(schemaDefinition());
    for (KSMetaData ksm : schemaDefinition()) {
      MigrationManager.announceNewKeyspace(ksm);
    }

      DatabaseDescriptor.loadSchemas(false);
  }

  public void leakDetect() throws InterruptedException {
    System.gc();
    System.gc();
    System.gc();
    Thread.sleep(10);
  }

  public static void prepareServer() {
    // Cleanup first
    // cleanupAndLeaveDirs();

    CommitLog.instance.allocator.enableReserveSegmentCreation();

    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      public void uncaughtException(Thread t, Throwable e) {
        logger.error("Fatal exception in thread " + t, e);
      }
    });

    Keyspace.setInitialized();
  }

  public static void startGossiper() {
    Gossiper.instance.start((int) (System.currentTimeMillis() / 1000));
  }

  public static void stopGossiper() {
    Gossiper.instance.stop();
  }

  public static Collection<KSMetaData> schemaDefinition() throws ConfigurationException {
    List<KSMetaData> schema = new ArrayList<KSMetaData>();

    // A whole bucket of shorthand
    String ks1 = "Keyspace1";


    Class<? extends AbstractReplicationStrategy> simple = SimpleStrategy.class;

    Map<String, String> opts_rf1 = KSMetaData.optsWithRF(1);
    AbstractType bytes = BytesType.instance;

    AbstractType<?> composite = CompositeType.getInstance(Arrays.asList(new AbstractType<?>[]{BytesType.instance, TimeUUIDType.instance, IntegerType.instance}));
    AbstractType<?> compositeMaxMin = CompositeType.getInstance(Arrays.asList(new AbstractType<?>[]{BytesType.instance, IntegerType.instance}));
    Map<Byte, AbstractType<?>> aliases = new HashMap<Byte, AbstractType<?>>();
    aliases.put((byte) 'b', BytesType.instance);
    aliases.put((byte) 't', TimeUUIDType.instance);
    aliases.put((byte) 'B', ReversedType.getInstance(BytesType.instance));
    aliases.put((byte) 'T', ReversedType.getInstance(TimeUUIDType.instance));
    AbstractType<?> dynamicComposite = DynamicCompositeType.getInstance(aliases);

    Map<String, String> leveledOptions = new HashMap<String, String>();
    leveledOptions.put("sstable_size_in_mb", "64");
    Map<String, String> compressionOptions = new HashMap<String, String>();
    compressionOptions.put("sstable_compression", "");


    // Keyspace 1
    schema.add(KSMetaData.testMetadata(ks1,
        simple,
        opts_rf1,

        // Column Families
        standardCFMD(ks1, "Standard1").compactionStrategyClass(LeveledCompactionStrategy.class)
            .maxIndexInterval(256)
            .compactionStrategyOptions(leveledOptions)
            .compressionParameters(CompressionParameters.create(compressionOptions))
    ));


    return schema;
  }


  private static void useCompression(List<KSMetaData> schema) {
    for (KSMetaData ksm : schema) {
      for (CFMetaData cfm : ksm.cfMetaData().values()) {
        cfm.compressionParameters(new CompressionParameters(SnappyCompressor.instance));
      }
    }
  }

  private static CFMetaData standardCFMD(String ksName, String cfName) {
    return CFMetaData.denseCFMetaData(ksName, cfName, BytesType.instance);
  }

  public static void cleanupAndLeaveDirs() {
    mkdirs();
    cleanup();
    mkdirs();
    CommitLog.instance.resetUnsafe(); // cleanup screws w/ CommitLog, this brings it back to safe state
  }

  public static void cleanup() {
    // clean up commitlog
    String[] directoryNames = {DatabaseDescriptor.getCommitLogLocation(),};
    for (String dirName : directoryNames) {
      File dir = new File(dirName);
      if (!dir.exists())
        throw new RuntimeException("No such directory: " + dir.getAbsolutePath());
      FileUtils.deleteRecursive(dir);
    }

    cleanupSavedCaches();

    // clean up data directory which are stored as data directory/keyspace/data files
    for (String dirName : DatabaseDescriptor.getAllDataFileLocations()) {
      File dir = new File(dirName);
      if (!dir.exists())
        throw new RuntimeException("No such directory: " + dir.getAbsolutePath());
      FileUtils.deleteRecursive(dir);
    }
  }

  public static void mkdirs() {
    DatabaseDescriptor.createAllDirectories();
  }


  protected static void cleanupSavedCaches() {
    File cachesDir = new File(DatabaseDescriptor.getSavedCachesLocation());

    if (!cachesDir.exists() || !cachesDir.isDirectory())
      return;

    FileUtils.delete(cachesDir.listFiles());
  }
}
