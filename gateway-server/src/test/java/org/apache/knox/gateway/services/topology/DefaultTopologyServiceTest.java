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
package org.apache.knox.gateway.services.topology;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.knox.gateway.config.GatewayConfig;
import org.apache.knox.gateway.services.topology.impl.DefaultTopologyService;
import org.apache.hadoop.test.TestUtils;
import org.apache.knox.gateway.topology.Param;
import org.apache.knox.gateway.topology.Provider;
import org.apache.knox.gateway.topology.Topology;
import org.apache.knox.gateway.topology.TopologyEvent;
import org.apache.knox.gateway.topology.TopologyListener;
import org.apache.knox.gateway.services.security.AliasService;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

import static org.easymock.EasyMock.anyObject;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class DefaultTopologyServiceTest {

  @Before
  public void setUp() throws Exception {
  }

  @After
  public void tearDown() throws Exception {
  }

  private File createDir() throws IOException {
    return TestUtils.createTempDir(this.getClass().getSimpleName() + "-");
  }

  private File createFile(File parent, String name, String resource, long timestamp) throws IOException {
    File file = new File(parent, name);
    if (!file.exists()) {
      FileUtils.touch(file);
    }
    InputStream input = ClassLoader.getSystemResourceAsStream(resource);
    OutputStream output = FileUtils.openOutputStream(file);
    IOUtils.copy(input, output);
    //KNOX-685: output.flush();
    input.close();
    output.close();
    file.setLastModified(timestamp);
    assertTrue("Failed to create test file " + file.getAbsolutePath(), file.exists());
    assertTrue("Failed to populate test file " + file.getAbsolutePath(), file.length() > 0);

    return file;
  }

  @Test
  public void testGetTopologies() throws Exception {

    File dir = createDir();
    File topologyDir = new File(dir, "topologies");

    File descriptorsDir = new File(dir, "descriptors");
    descriptorsDir.mkdirs();

    File sharedProvidersDir = new File(dir, "shared-providers");
    sharedProvidersDir.mkdirs();

    long time = topologyDir.lastModified();
    try {
      createFile(topologyDir, "one.xml", "org/apache/knox/gateway/topology/file/topology-one.xml", time);

      TestTopologyListener topoListener = new TestTopologyListener();
      FileAlterationMonitor monitor = new FileAlterationMonitor(Long.MAX_VALUE);

      TopologyService provider = new DefaultTopologyService();
      Map<String, String> c = new HashMap<>();

      GatewayConfig config = EasyMock.createNiceMock(GatewayConfig.class);
      EasyMock.expect(config.getGatewayTopologyDir()).andReturn(topologyDir.getAbsolutePath()).anyTimes();
      EasyMock.expect(config.getGatewayConfDir()).andReturn(descriptorsDir.getParentFile().getAbsolutePath()).anyTimes();
      EasyMock.replay(config);

      provider.init(config, c);

      provider.addTopologyChangeListener(topoListener);

      provider.reloadTopologies();

      Collection<Topology> topologies = provider.getTopologies();
      assertThat(topologies, notNullValue());
      assertThat(topologies.size(), is(1));
      Topology topology = topologies.iterator().next();
      assertThat(topology.getName(), is("one"));
      assertThat(topology.getTimestamp(), is(time));
      assertThat(topoListener.events.size(), is(1));
      topoListener.events.clear();

      // Add a file to the directory.
      File two = createFile(topologyDir, "two.xml",
          "org/apache/knox/gateway/topology/file/topology-two.xml", 1L);
      provider.reloadTopologies();
      topologies = provider.getTopologies();
      assertThat(topologies.size(), is(2));
      Set<String> names = new HashSet<>(Arrays.asList("one", "two"));
      Iterator<Topology> iterator = topologies.iterator();
      topology = iterator.next();
      assertThat(names, hasItem(topology.getName()));
      names.remove(topology.getName());
      topology = iterator.next();
      assertThat(names, hasItem(topology.getName()));
      names.remove(topology.getName());
      assertThat(names.size(), is(0));
      assertThat(topoListener.events.size(), is(1));
      List<TopologyEvent> events = topoListener.events.get(0);
      assertThat(events.size(), is(1));
      TopologyEvent event = events.get(0);
      assertThat(event.getType(), is(TopologyEvent.Type.CREATED));
      assertThat(event.getTopology(), notNullValue());

      // Update a file in the directory.
      two = createFile(topologyDir, "two.xml",
          "org/apache/knox/gateway/topology/file/topology-three.xml", 2L);
      provider.reloadTopologies();
      topologies = provider.getTopologies();
      assertThat(topologies.size(), is(2));
      names = new HashSet<>(Arrays.asList("one", "two"));
      iterator = topologies.iterator();
      topology = iterator.next();
      assertThat(names, hasItem(topology.getName()));
      names.remove(topology.getName());
      topology = iterator.next();
      assertThat(names, hasItem(topology.getName()));
      names.remove(topology.getName());
      assertThat(names.size(), is(0));

      // Remove a file from the directory.
      two.delete();
      provider.reloadTopologies();
      topologies = provider.getTopologies();
      assertThat(topologies.size(), is(1));
      topology = topologies.iterator().next();
      assertThat(topology.getName(), is("one"));
      assertThat(topology.getTimestamp(), is(time));

      // Add a simple descriptor to the descriptors dir to verify topology generation and loading (KNOX-1006)
      // N.B. This part of the test depends on the DummyServiceDiscovery extension being configured:
      //         org.apache.knox.gateway.topology.discovery.test.extension.DummyServiceDiscovery
      AliasService aliasService = EasyMock.createNiceMock(AliasService.class);
      EasyMock.expect(aliasService.getPasswordFromAliasForGateway(anyObject(String.class))).andReturn(null).anyTimes();
      EasyMock.replay(aliasService);
      DefaultTopologyService.DescriptorsMonitor dm =
                                          new DefaultTopologyService.DescriptorsMonitor(topologyDir, aliasService);

      // Write out the referenced provider config first
      File provCfgFile = createFile(sharedProvidersDir,
                                    "ambari-cluster-policy.xml",
          "org/apache/knox/gateway/topology/file/ambari-cluster-policy.xml",
                                    1L);
      try {
        // Create the simple descriptor in the descriptors dir
        File simpleDesc =
                createFile(descriptorsDir,
                           "four.json",
                    "org/apache/knox/gateway/topology/file/simple-topology-four.json",
                           1L);

        // Trigger the topology generation by noticing the simple descriptor
        dm.onFileChange(simpleDesc);

        // Load the generated topology
        provider.reloadTopologies();
        topologies = provider.getTopologies();
        assertThat(topologies.size(), is(2));
        names = new HashSet<>(Arrays.asList("one", "four"));
        iterator = topologies.iterator();
        topology = iterator.next();
        assertThat(names, hasItem(topology.getName()));
        names.remove(topology.getName());
        topology = iterator.next();
        assertThat(names, hasItem(topology.getName()));
        names.remove(topology.getName());
        assertThat(names.size(), is(0));
      } finally {
        provCfgFile.delete();

      }
    } finally {
      FileUtils.deleteQuietly(dir);
    }
  }

  private void kickMonitor(FileAlterationMonitor monitor) {
    for (FileAlterationObserver observer : monitor.getObservers()) {
      observer.checkAndNotify();
    }
  }

  @Test
  public void testProviderParamsOrderIsPreserved() {

    Provider provider = new Provider();
    String names[] = {"ldapRealm=",
        "ldapContextFactory",
        "ldapRealm.contextFactory",
        "ldapGroupRealm",
        "ldapGroupRealm.contextFactory",
        "ldapGroupRealm.contextFactory.systemAuthenticationMechanism"
    };

    Param param = null;
    for (String name : names) {
      param = new Param();
      param.setName(name);
      param.setValue(name);
      provider.addParam(param);

    }
    Map<String, String> params = provider.getParams();
    Set<String> keySet = params.keySet();
    Iterator<String> iter = keySet.iterator();
    int i = 0;
    while (iter.hasNext()) {
      assertTrue(iter.next().equals(names[i++]));
    }

  }

  private class TestTopologyListener implements TopologyListener {

    public ArrayList<List<TopologyEvent>> events = new ArrayList<List<TopologyEvent>>();

    @Override
    public void handleTopologyEvent(List<TopologyEvent> events) {
      this.events.add(events);
    }

  }

}
