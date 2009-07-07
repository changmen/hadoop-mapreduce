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

package org.apache.hadoop.mapred;

import java.io.IOException;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FakeObjectUtilities.FakeJobTracker;
import org.apache.hadoop.mapred.JobClient.RawSplit;
import org.apache.hadoop.mapred.UtilsForTests.FakeClock;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.StaticMapping;

/**
 * A JUnit test to test configured task limits.
 */
public class TestRackAwareTaskPlacement extends TestCase {

  static String trackers[] = new String[] {"tracker_tracker1.r1.com:1000", 
    "tracker_tracker2.r1.com:1000", "tracker_tracker3.r2.com:1000",
    "tracker_tracker4.r3.com:1000"};
  
  static String[] allHosts = 
    new String[] {"tracker1.r1.com", "tracker2.r1.com", "tracker3.r2.com",
                  "tracker4.r3.com"};
  
  static String[] allRacks =
    new String[] { "/r1", "/r1", "/r2", "/r3"};

  static FakeJobTracker jobTracker;
  static String jtIdentifier = "test";
  private static int jobCounter;
  
  public static Test suite() {
    TestSetup setup = 
      new TestSetup(new TestSuite(TestRackAwareTaskPlacement.class)) {
      protected void setUp() throws Exception {
        JobConf conf = new JobConf();
        conf.set("mapred.job.tracker", "localhost:0");
        conf.set("mapred.job.tracker.http.address", "0.0.0.0:0");
        conf.setClass("topology.node.switch.mapping.impl", 
          StaticMapping.class, DNSToSwitchMapping.class);
        jobTracker = new FakeJobTracker(conf, new FakeClock(), trackers);
        // Set up the Topology Information
        for (int i = 0; i < allHosts.length; i++) {
          StaticMapping.addNodeToRack(allHosts[i], allRacks[i]);
        }
        for (String tracker : trackers) {
          FakeObjectUtilities.establishFirstContact(jobTracker, tracker);
        }
      }
    };
    return setup;
  }
   
  static class MyFakeJobInProgress extends JobInProgress {
    static JobID jobid;
    int numMaps;

    MyFakeJobInProgress(JobConf jc, JobTracker jt) throws IOException {
      super((jobid = new JobID(jtIdentifier, jobCounter ++)), jc, jt);
      Path jobFile = new Path("Dummy");
      this.profile = new JobProfile(jc.getUser(), jobid, 
          jobFile.toString(), null, jc.getJobName(),
          jc.getQueueName());
      this.maxMapsPerNode = jc.getMaxMapsPerNode();
      this.maxReducesPerNode = jc.getMaxReducesPerNode();
      this.runningMapLimit = jc.getRunningMapLimit();
      this.runningReduceLimit = jc.getRunningReduceLimit();
    }

    @Override
    public void initTasks() throws IOException {
      JobClient.RawSplit[] splits = createSplits();
      numMapTasks = splits.length;
      createMapTasks(null, splits);
      nonRunningMapCache = createCache(splits, maxLevel);
      tasksInited.set(true);
      this.status.setRunState(JobStatus.RUNNING);

    }
  

    protected JobClient.RawSplit[] createSplits() throws IOException {
      RawSplit[] splits = new RawSplit[numMaps];
      // Hand code for now. 
      // M0,2,3 reside in Host1
      // M1 resides in Host3
      // M4 resides in Host4
      String[] splitHosts0 = new String[] { allHosts[0] };

      for (int i = 0; i < numMaps; i++) {
        splits[i] = new RawSplit();
        splits[i].setDataLength(0);
      }

      splits[0].setLocations(splitHosts0);
      splits[2].setLocations(splitHosts0);
      splits[3].setLocations(splitHosts0);
      
      String[] splitHosts1 = new String[] { allHosts[2] };
      splits[1].setLocations(splitHosts1);

      String[] splitHosts2 = new String[] { allHosts[3] };
      splits[4].setLocations(splitHosts2);

      return splits;
    }
  }
  @SuppressWarnings("deprecation")
  public void testTaskPlacement() throws IOException {
    JobConf conf = new JobConf();
    conf.setNumReduceTasks(0);
    conf.setJobName("TestTaskPlacement");
    
    MyFakeJobInProgress jip = new MyFakeJobInProgress(conf, jobTracker);
    jip.numMaps = 5;
    jip.initTasks();

    // Tracker1 should get a rack local
    TaskTrackerStatus tts = new TaskTrackerStatus(trackers[1], allHosts[1]);
    jip.obtainNewMapTask(tts, 4, 4);
    
    // Tracker0 should get a data local
    tts = new TaskTrackerStatus(trackers[0], allHosts[0]);
    jip.obtainNewMapTask(tts, 4, 4);

    // Tracker2 should get a data local
    tts = new TaskTrackerStatus(trackers[2], allHosts[2]);
    jip.obtainNewMapTask(tts, 4, 4);

    // Tracker0 should get a data local
    tts = new TaskTrackerStatus(trackers[0], allHosts[0]);
    jip.obtainNewMapTask(tts, 4, 4);

    // Tracker1 should not get any locality at all
    tts = new TaskTrackerStatus(trackers[1], allHosts[1]);
    jip.obtainNewMapTask(tts, 4, 4);

    
    Counters counters = jip.getCounters();
    assertEquals("Number of data local maps", 3,
        counters.getCounter(JobCounter.DATA_LOCAL_MAPS));
    
    assertEquals("Number of Rack-local maps", 1 , 
        counters.getCounter(JobCounter.RACK_LOCAL_MAPS));
    
    assertEquals("Number of Other-local maps", 0, 
        counters.getCounter(JobCounter.OTHER_LOCAL_MAPS));

  }
}