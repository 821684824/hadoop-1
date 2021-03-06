/**
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
package org.apache.hadoop.hdfs.server.datanode.fsdataset.impl;

import java.io.IOException;
import java.io.RandomAccessFile;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.StorageType;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;
import org.apache.hadoop.hdfs.server.datanode.FsDatasetTestUtils;
import org.apache.hadoop.hdfs.server.datanode.ReplicaAlreadyExistsException;
import org.apache.hadoop.hdfs.server.datanode.ReplicaBeingWritten;
import org.apache.hadoop.hdfs.server.datanode.ReplicaInPipelineInterface;
import org.apache.hadoop.hdfs.server.datanode.ReplicaInfo;
import org.apache.hadoop.hdfs.server.datanode.ReplicaNotFoundException;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsDatasetSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.datanode.ReplicaUnderRecovery;
import org.apache.hadoop.hdfs.server.datanode.ReplicaWaitingToBeRecovered;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.DiskChecker.DiskOutOfSpaceException;
import org.junit.Assert;
import org.junit.Test;

/** Test if FSDataset#append, writeToRbw, and writeToTmp */
public class TestWriteToReplica {

  final private static int FINALIZED = 0;
  final private static int TEMPORARY = 1;
  final private static int RBW = 2;
  final private static int RWR = 3;
  final private static int RUR = 4;
  final private static int NON_EXISTENT = 5;
  
  // test close
  @Test
  public void testClose() throws Exception {
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(new HdfsConfiguration()).build();
    
    try {
      cluster.waitActive();
      DataNode dn = cluster.getDataNodes().get(0);
      FsDatasetSpi<?> dataSet = DataNodeTestUtils.getFSDataset(dn);

      // set up replicasMap
      String bpid = cluster.getNamesystem().getBlockPoolId();
      
      ExtendedBlock[] blocks = setup(bpid, cluster.getFsDatasetTestUtils(dn));

      // test close
      testClose(dataSet, blocks);
    } finally {
      cluster.shutdown();
    }
  }

  // test append
  @Test
  public void testAppend() throws Exception {
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(new HdfsConfiguration()).build();
    try {
      cluster.waitActive();
      DataNode dn = cluster.getDataNodes().get(0);
      FsDatasetSpi<?> dataSet = DataNodeTestUtils.getFSDataset(dn);

      // set up replicasMap
      String bpid = cluster.getNamesystem().getBlockPoolId();
      ExtendedBlock[] blocks = setup(bpid, cluster.getFsDatasetTestUtils(dn));

      // test append
      testAppend(bpid, dataSet, blocks);
    } finally {
      cluster.shutdown();
    }
  }

  // test writeToRbw
  @Test
  public void testWriteToRbw() throws Exception {
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(new HdfsConfiguration()).build();
    try {
      cluster.waitActive();
      DataNode dn = cluster.getDataNodes().get(0);
      FsDatasetImpl dataSet = (FsDatasetImpl)DataNodeTestUtils.getFSDataset(dn);

      // set up replicasMap
      String bpid = cluster.getNamesystem().getBlockPoolId();
      ExtendedBlock[] blocks = setup(bpid, cluster.getFsDatasetTestUtils(dn));

      // test writeToRbw
      testWriteToRbw(dataSet, blocks);
    } finally {
      cluster.shutdown();
    }
  }
  
  // test writeToTemporary
  @Test
  public void testWriteToTemporary() throws Exception {
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(new HdfsConfiguration()).build();
    try {
      cluster.waitActive();
      DataNode dn = cluster.getDataNodes().get(0);
      FsDatasetImpl dataSet = (FsDatasetImpl)DataNodeTestUtils.getFSDataset(dn);

      // set up replicasMap
      String bpid = cluster.getNamesystem().getBlockPoolId();
      ExtendedBlock[] blocks = setup(bpid, cluster.getFsDatasetTestUtils(dn));

      // test writeToTemporary
      testWriteToTemporary(dataSet, blocks);
    } finally {
      cluster.shutdown();
    }
  }
  
  /**
   * Generate testing environment and return a collection of blocks
   * on which to run the tests.
   * 
   * @param bpid Block pool ID to generate blocks for
   * @param testUtils FsDatasetTestUtils provides white box access to FsDataset.
   * @return Contrived blocks for further testing.
   * @throws IOException
   */
  private ExtendedBlock[] setup(String bpid, FsDatasetTestUtils testUtils)
      throws IOException {
    // setup replicas map
    
    ExtendedBlock[] blocks = new ExtendedBlock[] {
        new ExtendedBlock(bpid, 1, 1, 2001), new ExtendedBlock(bpid, 2, 1, 2002), 
        new ExtendedBlock(bpid, 3, 2, 2003), new ExtendedBlock(bpid, 4, 1, 2004),
        new ExtendedBlock(bpid, 5, 1, 2005), new ExtendedBlock(bpid, 6, 1, 2006)
    };

    testUtils.createFinalizedReplica(blocks[FINALIZED]);
    testUtils.createReplicaInPipeline(blocks[TEMPORARY]);
    testUtils.createRBW(blocks[RBW]);
    testUtils.createReplicaWaitingToBeRecovered(blocks[RWR]);
    testUtils.createReplicaUnderRecovery(blocks[RUR], 2007);
    
    return blocks;
  }
  
  private void testAppend(String bpid, FsDatasetSpi<?> dataSet,
                          ExtendedBlock[] blocks) throws IOException {
    long newGS = blocks[FINALIZED].getGenerationStamp()+1;
    final FsVolumeSpi v = dataSet.getVolume(blocks[FINALIZED]);
    if (v instanceof FsVolumeImpl) {
      FsVolumeImpl fvi = (FsVolumeImpl) v;
      long available = fvi.getCapacity() - fvi.getDfsUsed();
      long expectedLen = blocks[FINALIZED].getNumBytes();
      try {
        fvi.decDfsUsed(bpid, -available);
        blocks[FINALIZED].setNumBytes(expectedLen + 100);
        dataSet.append(blocks[FINALIZED], newGS, expectedLen);
        Assert.fail("Should not have space to append to an RWR replica" + blocks[RWR]);
      } catch (DiskOutOfSpaceException e) {
        Assert.assertTrue(e.getMessage().startsWith(
            "Insufficient space for appending to "));
      }
      fvi.decDfsUsed(bpid, available);
      blocks[FINALIZED].setNumBytes(expectedLen);
    }

    newGS = blocks[RBW].getGenerationStamp()+1;
    dataSet.append(blocks[FINALIZED], newGS, 
        blocks[FINALIZED].getNumBytes());  // successful
    blocks[FINALIZED].setGenerationStamp(newGS);
        
    try {
      dataSet.append(blocks[TEMPORARY], blocks[TEMPORARY].getGenerationStamp()+1, 
          blocks[TEMPORARY].getNumBytes());
      Assert.fail("Should not have appended to a temporary replica " 
          + blocks[TEMPORARY]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertEquals(ReplicaNotFoundException.UNFINALIZED_REPLICA +
          blocks[TEMPORARY], e.getMessage());
    }

    try {
      dataSet.append(blocks[RBW], blocks[RBW].getGenerationStamp()+1,
          blocks[RBW].getNumBytes());
      Assert.fail("Should not have appended to an RBW replica" + blocks[RBW]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertEquals(ReplicaNotFoundException.UNFINALIZED_REPLICA +
          blocks[RBW], e.getMessage());
    }

    try {
      dataSet.append(blocks[RWR], blocks[RWR].getGenerationStamp()+1,
          blocks[RBW].getNumBytes());
      Assert.fail("Should not have appended to an RWR replica" + blocks[RWR]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertEquals(ReplicaNotFoundException.UNFINALIZED_REPLICA +
          blocks[RWR], e.getMessage());
    }

    try {
      dataSet.append(blocks[RUR], blocks[RUR].getGenerationStamp()+1,
          blocks[RUR].getNumBytes());
      Assert.fail("Should not have appended to an RUR replica" + blocks[RUR]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertEquals(ReplicaNotFoundException.UNFINALIZED_REPLICA +
          blocks[RUR], e.getMessage());
    }

    try {
      dataSet.append(blocks[NON_EXISTENT], 
          blocks[NON_EXISTENT].getGenerationStamp(), 
          blocks[NON_EXISTENT].getNumBytes());
      Assert.fail("Should not have appended to a non-existent replica " + 
          blocks[NON_EXISTENT]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertEquals(ReplicaNotFoundException.NON_EXISTENT_REPLICA + 
          blocks[NON_EXISTENT], e.getMessage());
    }
    
    newGS = blocks[FINALIZED].getGenerationStamp()+1;
    dataSet.recoverAppend(blocks[FINALIZED], newGS, 
        blocks[FINALIZED].getNumBytes());  // successful
    blocks[FINALIZED].setGenerationStamp(newGS);
    
    try {
      dataSet.recoverAppend(blocks[TEMPORARY], blocks[TEMPORARY].getGenerationStamp()+1, 
          blocks[TEMPORARY].getNumBytes());
      Assert.fail("Should not have appended to a temporary replica " 
          + blocks[TEMPORARY]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.UNFINALIZED_AND_NONRBW_REPLICA));
    }

    newGS = blocks[RBW].getGenerationStamp()+1;
    dataSet.recoverAppend(blocks[RBW], newGS, blocks[RBW].getNumBytes());
    blocks[RBW].setGenerationStamp(newGS);

    try {
      dataSet.recoverAppend(blocks[RWR], blocks[RWR].getGenerationStamp()+1,
          blocks[RBW].getNumBytes());
      Assert.fail("Should not have appended to an RWR replica" + blocks[RWR]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.UNFINALIZED_AND_NONRBW_REPLICA));
    }

    try {
      dataSet.recoverAppend(blocks[RUR], blocks[RUR].getGenerationStamp()+1,
          blocks[RUR].getNumBytes());
      Assert.fail("Should not have appended to an RUR replica" + blocks[RUR]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.UNFINALIZED_AND_NONRBW_REPLICA));
    }

    try {
      dataSet.recoverAppend(blocks[NON_EXISTENT], 
          blocks[NON_EXISTENT].getGenerationStamp(), 
          blocks[NON_EXISTENT].getNumBytes());
      Assert.fail("Should not have appended to a non-existent replica " + 
          blocks[NON_EXISTENT]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.NON_EXISTENT_REPLICA));
    }
  }

  private void testClose(FsDatasetSpi<?> dataSet, ExtendedBlock [] blocks) throws IOException {
    long newGS = blocks[FINALIZED].getGenerationStamp()+1;
    dataSet.recoverClose(blocks[FINALIZED], newGS, 
        blocks[FINALIZED].getNumBytes());  // successful
    blocks[FINALIZED].setGenerationStamp(newGS);
    
    try {
      dataSet.recoverClose(blocks[TEMPORARY], blocks[TEMPORARY].getGenerationStamp()+1, 
          blocks[TEMPORARY].getNumBytes());
      Assert.fail("Should not have recovered close a temporary replica " 
          + blocks[TEMPORARY]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.UNFINALIZED_AND_NONRBW_REPLICA));
    }

    newGS = blocks[RBW].getGenerationStamp()+1;
    dataSet.recoverClose(blocks[RBW], newGS, blocks[RBW].getNumBytes());
    blocks[RBW].setGenerationStamp(newGS);

    try {
      dataSet.recoverClose(blocks[RWR], blocks[RWR].getGenerationStamp()+1,
          blocks[RBW].getNumBytes());
      Assert.fail("Should not have recovered close an RWR replica" + blocks[RWR]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.UNFINALIZED_AND_NONRBW_REPLICA));
    }

    try {
      dataSet.recoverClose(blocks[RUR], blocks[RUR].getGenerationStamp()+1,
          blocks[RUR].getNumBytes());
      Assert.fail("Should not have recovered close an RUR replica" + blocks[RUR]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.UNFINALIZED_AND_NONRBW_REPLICA));
    }

    try {
      dataSet.recoverClose(blocks[NON_EXISTENT], 
          blocks[NON_EXISTENT].getGenerationStamp(), 
          blocks[NON_EXISTENT].getNumBytes());
      Assert.fail("Should not have recovered close a non-existent replica " + 
          blocks[NON_EXISTENT]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.NON_EXISTENT_REPLICA));
    }
  }
  
  private void testWriteToRbw(FsDatasetImpl dataSet, ExtendedBlock[] blocks) throws IOException {
    try {
      dataSet.recoverRbw(blocks[FINALIZED],
          blocks[FINALIZED].getGenerationStamp()+1,
          0L, blocks[FINALIZED].getNumBytes());
      Assert.fail("Should not have recovered a finalized replica " +
          blocks[FINALIZED]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.NON_RBW_REPLICA));
    }
 
    try {
      dataSet.createRbw(StorageType.DEFAULT, blocks[FINALIZED], false);
      Assert.fail("Should not have created a replica that's already " +
      		"finalized " + blocks[FINALIZED]);
    } catch (ReplicaAlreadyExistsException e) {
    }
 
    try {
      dataSet.recoverRbw(blocks[TEMPORARY], 
          blocks[TEMPORARY].getGenerationStamp()+1, 
          0L, blocks[TEMPORARY].getNumBytes());
      Assert.fail("Should not have recovered a temporary replica " +
          blocks[TEMPORARY]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.NON_RBW_REPLICA));
    }

    try {
      dataSet.createRbw(StorageType.DEFAULT, blocks[TEMPORARY], false);
      Assert.fail("Should not have created a replica that had created as " +
      		"temporary " + blocks[TEMPORARY]);
    } catch (ReplicaAlreadyExistsException e) {
    }
        
    dataSet.recoverRbw(blocks[RBW], blocks[RBW].getGenerationStamp()+1, 
        0L, blocks[RBW].getNumBytes());  // expect to be successful
    
    try {
      dataSet.createRbw(StorageType.DEFAULT, blocks[RBW], false);
      Assert.fail("Should not have created a replica that had created as RBW " +
          blocks[RBW]);
    } catch (ReplicaAlreadyExistsException e) {
    }
    
    try {
      dataSet.recoverRbw(blocks[RWR], blocks[RWR].getGenerationStamp()+1,
          0L, blocks[RWR].getNumBytes());
      Assert.fail("Should not have recovered a RWR replica " + blocks[RWR]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.NON_RBW_REPLICA));
    }

    try {
      dataSet.createRbw(StorageType.DEFAULT, blocks[RWR], false);
      Assert.fail("Should not have created a replica that was waiting to be " +
      		"recovered " + blocks[RWR]);
    } catch (ReplicaAlreadyExistsException e) {
    }
    
    try {
      dataSet.recoverRbw(blocks[RUR], blocks[RUR].getGenerationStamp()+1,
          0L, blocks[RUR].getNumBytes());
      Assert.fail("Should not have recovered a RUR replica " + blocks[RUR]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          ReplicaNotFoundException.NON_RBW_REPLICA));
    }

    try {
      dataSet.createRbw(StorageType.DEFAULT, blocks[RUR], false);
      Assert.fail("Should not have created a replica that was under recovery " +
          blocks[RUR]);
    } catch (ReplicaAlreadyExistsException e) {
    }
    
    try {
      dataSet.recoverRbw(blocks[NON_EXISTENT],
          blocks[NON_EXISTENT].getGenerationStamp()+1,
          0L, blocks[NON_EXISTENT].getNumBytes());
      Assert.fail("Cannot recover a non-existent replica " +
          blocks[NON_EXISTENT]);
    } catch (ReplicaNotFoundException e) {
      Assert.assertTrue(
          e.getMessage().contains(ReplicaNotFoundException.NON_EXISTENT_REPLICA));
    }
    
    dataSet.createRbw(StorageType.DEFAULT, blocks[NON_EXISTENT], false);
  }
  
  private void testWriteToTemporary(FsDatasetImpl dataSet, ExtendedBlock[] blocks) throws IOException {
    try {
      dataSet.createTemporary(StorageType.DEFAULT, blocks[FINALIZED]);
      Assert.fail("Should not have created a temporary replica that was " +
      		"finalized " + blocks[FINALIZED]);
    } catch (ReplicaAlreadyExistsException e) {
    }
 
    try {
      dataSet.createTemporary(StorageType.DEFAULT, blocks[TEMPORARY]);
      Assert.fail("Should not have created a replica that had created as" +
      		"temporary " + blocks[TEMPORARY]);
    } catch (ReplicaAlreadyExistsException e) {
    }
    
    try {
      dataSet.createTemporary(StorageType.DEFAULT, blocks[RBW]);
      Assert.fail("Should not have created a replica that had created as RBW " +
          blocks[RBW]);
    } catch (ReplicaAlreadyExistsException e) {
    }
    
    try {
      dataSet.createTemporary(StorageType.DEFAULT, blocks[RWR]);
      Assert.fail("Should not have created a replica that was waiting to be " +
      		"recovered " + blocks[RWR]);
    } catch (ReplicaAlreadyExistsException e) {
    }
    
    try {
      dataSet.createTemporary(StorageType.DEFAULT, blocks[RUR]);
      Assert.fail("Should not have created a replica that was under recovery " +
          blocks[RUR]);
    } catch (ReplicaAlreadyExistsException e) {
    }
    
    dataSet.createTemporary(StorageType.DEFAULT, blocks[NON_EXISTENT]);

    try {
      dataSet.createTemporary(StorageType.DEFAULT, blocks[NON_EXISTENT]);
      Assert.fail("Should not have created a replica that had already been "
          + "created " + blocks[NON_EXISTENT]);
    } catch (Exception e) {
      Assert.assertTrue(
          e.getMessage().contains(blocks[NON_EXISTENT].getBlockName()));
      Assert.assertTrue(e instanceof ReplicaAlreadyExistsException);
    }

    long newGenStamp = blocks[NON_EXISTENT].getGenerationStamp() * 10;
    blocks[NON_EXISTENT].setGenerationStamp(newGenStamp);
    try {
      ReplicaInPipelineInterface replicaInfo =
          dataSet.createTemporary(StorageType.DEFAULT, blocks[NON_EXISTENT]).getReplica();
      Assert.assertTrue(replicaInfo.getGenerationStamp() == newGenStamp);
      Assert.assertTrue(
          replicaInfo.getBlockId() == blocks[NON_EXISTENT].getBlockId());
    } catch (ReplicaAlreadyExistsException e) {
      Assert.fail("createRbw() Should have removed the block with the older "
          + "genstamp and replaced it with the newer one: " + blocks[NON_EXISTENT]);
    }
  }

  /**
   * Test that we can successfully recover a {@link ReplicaBeingWritten}
   * which has inconsistent metadata (bytes were written to disk but bytesOnDisk
   * was not updated) but that recovery fails when the block is actually
   * corrupt (bytes are not present on disk).
   */
  @Test
  public void testRecoverInconsistentRbw() throws IOException {
    Configuration conf = new HdfsConfiguration();
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    cluster.waitActive();
    DataNode dn = cluster.getDataNodes().get(0);
    FsDatasetImpl fsDataset = (FsDatasetImpl)DataNodeTestUtils.getFSDataset(dn);

    // set up replicasMap
    String bpid = cluster.getNamesystem().getBlockPoolId();
    ExtendedBlock[] blocks = setup(bpid, cluster.getFsDatasetTestUtils(dn));

    ReplicaBeingWritten rbw = (ReplicaBeingWritten)fsDataset.
        getReplicaInfo(blocks[RBW]);
    long bytesOnDisk = rbw.getBytesOnDisk();
    // simulate an inconsistent replica length update by reducing in-memory
    // value of on disk length
    rbw.setLastChecksumAndDataLen(bytesOnDisk - 1, null);
    fsDataset.recoverRbw(blocks[RBW], blocks[RBW].getGenerationStamp(), 0L,
        rbw.getNumBytes());
    // after the recovery, on disk length should equal acknowledged length.
    Assert.assertTrue(rbw.getBytesOnDisk() == rbw.getBytesAcked());

    // reduce on disk length again; this time actually truncate the file to
    // simulate the data not being present
    rbw.setLastChecksumAndDataLen(bytesOnDisk - 1, null);
    try (RandomAccessFile blockRAF =
        new RandomAccessFile(rbw.getBlockFile(), "rw")) {
      // truncate blockFile
      blockRAF.setLength(bytesOnDisk - 1);
      fsDataset.recoverRbw(blocks[RBW], blocks[RBW].getGenerationStamp(), 0L,
          rbw.getNumBytes());
      Assert.fail("recovery should have failed");
    } catch (ReplicaNotFoundException rnfe) {
      GenericTestUtils.assertExceptionContains("Found fewer bytesOnDisk than " +
          "bytesAcked for replica", rnfe);
    }
  }
}
