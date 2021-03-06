/**
 * Copyright (c) 2014 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package com.couchbase.client.core.config.refresher;

import com.couchbase.client.core.ClusterFacade;
import com.couchbase.client.core.CouchbaseException;
import com.couchbase.client.core.config.BucketConfig;
import com.couchbase.client.core.config.ClusterConfig;
import com.couchbase.client.core.config.ConfigurationProvider;
import com.couchbase.client.core.config.DefaultNodeInfo;
import com.couchbase.client.core.config.NodeInfo;
import com.couchbase.client.core.endpoint.kv.KeyValueStatus;
import com.couchbase.client.core.env.CoreEnvironment;
import com.couchbase.client.core.env.DefaultCoreEnvironment;
import com.couchbase.client.core.message.CouchbaseResponse;
import com.couchbase.client.core.message.ResponseStatus;
import com.couchbase.client.core.message.kv.GetBucketConfigRequest;
import com.couchbase.client.core.message.kv.GetBucketConfigResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import rx.Observable;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the functionality of the {@link CarrierRefresher}.
 *
 * @author Michael Nitschinger
 * @since 1.0.3
 */
public class CarrierRefresherTest {

    private static final CoreEnvironment ENVIRONMENT = DefaultCoreEnvironment.create();

    @Test
    public void shouldProposeConfigFromTaintedPoller() throws Exception {
        ClusterFacade cluster = mock(ClusterFacade.class);
        ConfigurationProvider provider = mock(ConfigurationProvider.class);
        BucketConfig config = mock(BucketConfig.class);

        CarrierRefresher refresher = new CarrierRefresher(ENVIRONMENT, cluster);
        refresher.provider(provider);

        when(config.name()).thenReturn("bucket");
        List<NodeInfo> nodeInfos = new ArrayList<NodeInfo>();
        Map<String, Integer> ports = new HashMap<String, Integer>();
        ports.put("direct", 11210);
        nodeInfos.add(new DefaultNodeInfo(null, "localhost:8091", ports));
        when(config.nodes()).thenReturn(nodeInfos);

        final AtomicReference<ByteBuf> bufRef = new AtomicReference<ByteBuf>(null);
        when(cluster.send(any(GetBucketConfigRequest.class)))
                .thenAnswer(new Answer<Observable<GetBucketConfigResponse>>() {
                    @Override
                    public Observable<GetBucketConfigResponse> answer(InvocationOnMock invocation) throws Throwable {
                        ByteBuf content = Unpooled.copiedBuffer("{\"config\": true}", CharsetUtil.UTF_8);
                        ByteBuf oldContent = bufRef.getAndSet(content);
                        if (oldContent != null) {
                            assertEquals(0, oldContent.refCnt());
                        }
                        GetBucketConfigResponse response = new GetBucketConfigResponse(
                                ResponseStatus.SUCCESS, KeyValueStatus.SUCCESS.code(),
                                "bucket",
                                content,
                                InetAddress.getByName("localhost"));
                        return Observable.just(response);
                    }
                });

        refresher.markTainted(config);

        Thread.sleep(1500);

        verify(provider, times(1)).proposeBucketConfig("bucket", "{\"config\": true}");
        assertEquals(0, bufRef.get().refCnt());
    }

    @Test
    public void shouldNotProposeInvalidConfigFromTaintedPoller() throws Exception {
        ClusterFacade cluster = mock(ClusterFacade.class);
        ConfigurationProvider provider = mock(ConfigurationProvider.class);
        BucketConfig config = mock(BucketConfig.class);

        CarrierRefresher refresher = new CarrierRefresher(ENVIRONMENT, cluster);
        refresher.provider(provider);

        when(config.name()).thenReturn("bucket");
        List<NodeInfo> nodeInfos = new ArrayList<NodeInfo>();
        Map<String, Integer> ports = new HashMap<String, Integer>();
        ports.put("direct", 11210);
        nodeInfos.add(new DefaultNodeInfo(null, "localhost:8091", ports));
        when(config.nodes()).thenReturn(nodeInfos);

        ByteBuf content = Unpooled.copiedBuffer("", CharsetUtil.UTF_8);
        when(cluster.send(any(GetBucketConfigRequest.class))).thenReturn(Observable.just(
            (CouchbaseResponse) new GetBucketConfigResponse(
                ResponseStatus.FAILURE, KeyValueStatus.ERR_NOT_FOUND.code(),
                "bucket",
                content,
                InetAddress.getByName("localhost")
            )
        ));

        refresher.markTainted(config);

        Thread.sleep(1500);

        verify(provider, never()).proposeBucketConfig("bucket", "");
        assertEquals(0, content.refCnt());
    }

    @Test
    public void shouldRefreshWithValidClusterConfig() throws Exception {
        ClusterFacade cluster = mock(ClusterFacade.class);
        CarrierRefresher refresher = new CarrierRefresher(ENVIRONMENT, cluster);
        refresher.registerBucket("bucket", "");
        ConfigurationProvider provider = mock(ConfigurationProvider.class);
        refresher.provider(provider);

        ClusterConfig clusterConfig = mock(ClusterConfig.class);
        BucketConfig bucketConfig = mock(BucketConfig.class);
        when(bucketConfig.name()).thenReturn("bucket");
        List<NodeInfo> nodeInfos = new ArrayList<NodeInfo>();
        Map<String, Integer> ports = new HashMap<String, Integer>();
        ports.put("direct", 11210);
        nodeInfos.add(new DefaultNodeInfo(null, "localhost:8091", ports));
        when(bucketConfig.nodes()).thenReturn(nodeInfos);
        Map<String, BucketConfig> bucketConfigs = new HashMap<String, BucketConfig>();
        bucketConfigs.put("bucket", bucketConfig);

        when(clusterConfig.bucketConfigs()).thenReturn(bucketConfigs);

        ByteBuf content = Unpooled.copiedBuffer("{\"config\": true}", CharsetUtil.UTF_8);
        when(cluster.send(any(GetBucketConfigRequest.class))).thenReturn(Observable.just(
            (CouchbaseResponse) new GetBucketConfigResponse(
                ResponseStatus.SUCCESS, KeyValueStatus.SUCCESS.code(),
                "bucket",
                content,
                InetAddress.getByName("localhost")
            )
        ));

        refresher.refresh(clusterConfig);

        Thread.sleep(200);

        verify(provider, times(1)).proposeBucketConfig("bucket", "{\"config\": true}");
        assertEquals(0, content.refCnt());
    }

    @Test
    public void shouldNotRefreshWithInvalidClusterConfig() throws Exception {
        ClusterFacade cluster = mock(ClusterFacade.class);
        CarrierRefresher refresher = new CarrierRefresher(ENVIRONMENT, cluster);
        refresher.registerBucket("bucket", "");
        ConfigurationProvider provider = mock(ConfigurationProvider.class);
        refresher.provider(provider);

        ClusterConfig clusterConfig = mock(ClusterConfig.class);
        BucketConfig bucketConfig = mock(BucketConfig.class);
        when(bucketConfig.name()).thenReturn("bucket");
        List<NodeInfo> nodeInfos = new ArrayList<NodeInfo>();

        Map<String, Integer> ports = new HashMap<String, Integer>();
        ports.put("direct", 11210);
        nodeInfos.add(new DefaultNodeInfo(null, "localhost:8091", ports));
        when(bucketConfig.nodes()).thenReturn(nodeInfos);
        Map<String, BucketConfig> bucketConfigs = new HashMap<String, BucketConfig>();
        bucketConfigs.put("bucket", bucketConfig);

        when(clusterConfig.bucketConfigs()).thenReturn(bucketConfigs);

        ByteBuf content = Unpooled.copiedBuffer("", CharsetUtil.UTF_8);
        when(cluster.send(any(GetBucketConfigRequest.class))).thenReturn(Observable.just(
            (CouchbaseResponse) new GetBucketConfigResponse(
                ResponseStatus.FAILURE, KeyValueStatus.ERR_NOT_FOUND.code(),
                "bucket",
                content,
                InetAddress.getByName("localhost")
            )
        ));

        refresher.refresh(clusterConfig);

        Thread.sleep(200);

        verify(provider, never()).proposeBucketConfig("bucket", "");
        assertEquals(0, content.refCnt());
    }

    @Test
    public void shouldFallbackToNextOnRefreshWhenFirstFails() throws Exception {
        ClusterFacade cluster = mock(ClusterFacade.class);
        CarrierRefresher refresher = new CarrierRefresher(ENVIRONMENT, cluster);
        refresher.registerBucket("bucket", "");
        ConfigurationProvider provider = mock(ConfigurationProvider.class);
        refresher.provider(provider);

        ClusterConfig clusterConfig = mock(ClusterConfig.class);
        BucketConfig bucketConfig = mock(BucketConfig.class);
        when(bucketConfig.name()).thenReturn("bucket");
        List<NodeInfo> nodeInfos = new ArrayList<NodeInfo>();

        Map<String, Integer> ports = new HashMap<String, Integer>();
        ports.put("direct", 11210);
        nodeInfos.add(new DefaultNodeInfo(null, "1.2.3.4:8091", ports));
        nodeInfos.add(new DefaultNodeInfo(null, "2.3.4.5:8091", ports));
        when(bucketConfig.nodes()).thenReturn(nodeInfos);
        Map<String, BucketConfig> bucketConfigs = new HashMap<String, BucketConfig>();
        bucketConfigs.put("bucket", bucketConfig);

        when(clusterConfig.bucketConfigs()).thenReturn(bucketConfigs);

        ByteBuf content = Unpooled.copiedBuffer("{\"config\": true}", CharsetUtil.UTF_8);
        Observable<CouchbaseResponse> goodResponse = Observable.just(
            (CouchbaseResponse) new GetBucketConfigResponse(
                ResponseStatus.SUCCESS, KeyValueStatus.SUCCESS.code(),
                "bucket",
                content,
                InetAddress.getByName("1.2.3.4")
            )
        );
        Observable<CouchbaseResponse> badResponse = Observable.error(new CouchbaseException("Woops.."));
        when(cluster.send(any(GetBucketConfigRequest.class))).thenReturn(badResponse, goodResponse);

        refresher.refresh(clusterConfig);

        Thread.sleep(1500);

        verify(provider, times(1)).proposeBucketConfig("bucket", "{\"config\": true}");
        assertEquals(0, content.refCnt());
    }

    @Test
    public void shouldFallbackToNextOnPollWhenFirstFails() throws Exception {
        ClusterFacade cluster = mock(ClusterFacade.class);
        ConfigurationProvider provider = mock(ConfigurationProvider.class);
        BucketConfig config = mock(BucketConfig.class);

        CarrierRefresher refresher = new CarrierRefresher(ENVIRONMENT, cluster);
        refresher.provider(provider);

        when(config.name()).thenReturn("bucket");
        List<NodeInfo> nodeInfos = new ArrayList<NodeInfo>();

        Map<String, Integer> ports = new HashMap<String, Integer>();
        ports.put("direct", 11210);
        nodeInfos.add(new DefaultNodeInfo(null, "1.2.3.4:8091", ports));
        nodeInfos.add(new DefaultNodeInfo(null, "2.3.4.5:8091", ports));
        when(config.nodes()).thenReturn(nodeInfos);

        ByteBuf content = Unpooled.copiedBuffer("{\"config\": true}", CharsetUtil.UTF_8);
        Observable<CouchbaseResponse> goodResponse = Observable.just((CouchbaseResponse) new GetBucketConfigResponse(
            ResponseStatus.SUCCESS, KeyValueStatus.SUCCESS.code(),
            "bucket",
            content,
            InetAddress.getByName("1.2.3.4")
        ));
        Observable<CouchbaseResponse> badResponse = Observable.error(new CouchbaseException("Failure"));
        when(cluster.send(any(GetBucketConfigRequest.class))).thenReturn(badResponse, goodResponse);
        refresher.markTainted(config);

        Thread.sleep(1500);

        verify(provider, times(1)).proposeBucketConfig("bucket", "{\"config\": true}");
        assertEquals(0, content.refCnt());
    }

    @Test
    public void shouldIgnoreNodeWithoutKVServiceEnabled() throws Exception {
        ClusterFacade cluster = mock(ClusterFacade.class);
        ConfigurationProvider provider = mock(ConfigurationProvider.class);
        BucketConfig config = mock(BucketConfig.class);

        CarrierRefresher refresher = new CarrierRefresher(ENVIRONMENT, cluster);
        refresher.provider(provider);

        when(config.name()).thenReturn("bucket");
        List<NodeInfo> nodeInfos = new ArrayList<NodeInfo>();

        Map<String, Integer> ports = new HashMap<String, Integer>();
        ports.put("direct", 11210);
        nodeInfos.add(new DefaultNodeInfo(null, "1.2.3.4:8091", ports));
        nodeInfos.add(new DefaultNodeInfo(null, "6.7.8.9:8091", new HashMap<String, Integer>()));
        nodeInfos.add(new DefaultNodeInfo(null, "2.3.4.5:8091", ports));
        when(config.nodes()).thenReturn(nodeInfos);

        ByteBuf content = Unpooled.copiedBuffer("{\"config\": true}", CharsetUtil.UTF_8);
        Observable<CouchbaseResponse> goodResponse = Observable.just((CouchbaseResponse) new GetBucketConfigResponse(
                ResponseStatus.SUCCESS, KeyValueStatus.SUCCESS.code(),
                "bucket",
                content,
                InetAddress.getByName("1.2.3.4")
        ));
        Observable<CouchbaseResponse> badResponse = Observable.error(new CouchbaseException("Failure"));
        when(cluster.send(any(GetBucketConfigRequest.class))).thenReturn(badResponse, goodResponse);
        refresher.markTainted(config);

        Thread.sleep(1500);

        verify(provider, times(1)).proposeBucketConfig("bucket", "{\"config\": true}");
        assertEquals(0, content.refCnt());
    }

}
