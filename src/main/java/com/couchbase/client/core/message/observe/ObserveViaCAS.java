/*
 * Copyright (c) 2015 Couchbase, Inc.
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

package com.couchbase.client.core.message.observe;

import com.couchbase.client.core.ClusterFacade;
import com.couchbase.client.core.DocumentConcurrentlyModifiedException;
import com.couchbase.client.core.ReplicaNotConfiguredException;
import com.couchbase.client.core.annotations.InterfaceAudience;
import com.couchbase.client.core.annotations.InterfaceStability;
import com.couchbase.client.core.config.CouchbaseBucketConfig;
import com.couchbase.client.core.message.cluster.GetClusterConfigRequest;
import com.couchbase.client.core.message.cluster.GetClusterConfigResponse;
import com.couchbase.client.core.message.kv.ObserveRequest;
import com.couchbase.client.core.message.kv.ObserveResponse;
import com.couchbase.client.core.retry.RetryStrategy;
import com.couchbase.client.core.time.Delay;
import rx.Observable;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to handle observe calls and polling logic.
 *
 * @author Michael Nitschinger
 * @since 1.0.1
 */
@InterfaceStability.Uncommitted
@InterfaceAudience.Private
public class ObserveViaCAS {

    public static Observable<Boolean> call(final ClusterFacade core, final String bucket, final String id,
        final long cas, final boolean remove, final Observe.PersistTo persistTo, final Observe.ReplicateTo replicateTo,
        final Delay delay, final RetryStrategy retryStrategy) {

        final ObserveResponse.ObserveStatus persistIdentifier;
        final ObserveResponse.ObserveStatus replicaIdentifier;
        if (remove) {
            persistIdentifier = ObserveResponse.ObserveStatus.NOT_FOUND_PERSISTED;
            replicaIdentifier = ObserveResponse.ObserveStatus.NOT_FOUND_NOT_PERSISTED;
        } else {
            persistIdentifier = ObserveResponse.ObserveStatus.FOUND_PERSISTED;
            replicaIdentifier = ObserveResponse.ObserveStatus.FOUND_NOT_PERSISTED;
        }

        Observable<ObserveResponse> observeResponses = sendObserveRequests(core, bucket, id, cas, persistTo,
            replicateTo, retryStrategy);

        return observeResponses
                //each response is converted into an ObserveItem state
                .map(new Func1<ObserveResponse, ObserveItem>() {
                    @Override
                    public ObserveItem call(ObserveResponse observeResponse) {
                        return new ObserveItem(id, observeResponse, cas, remove, persistIdentifier, replicaIdentifier);
                    }
                })
                //scan will aggregate each individual state and emit the intermediate states.
                //This allows for the minimum number of checks to occur before finding out that our criteria has been met.
                .scan(new ObserveItem(),
                        new Func2<ObserveItem, ObserveItem, ObserveItem>() {
                            @Override
                            public ObserveItem call(ObserveItem currentStatus, ObserveItem newStatus) {
                                return currentStatus.add(newStatus);
                            }
                        })
                //repetitions will occur unless errors are raised
                .repeatWhen(new Func1<Observable<? extends Void>, Observable<?>>() {
                    @Override
                    public Observable<?> call(Observable<? extends Void> observable) {
                        return observable.zipWith(
                            Observable.range(1, Integer.MAX_VALUE),
                            new Func2<Void, Integer, Integer>() {
                                @Override
                                public Integer call(Void aVoid, Integer attempt) {
                                    return attempt;
                                }
                            }
                        )
                        .flatMap(new Func1<Integer, Observable<?>>() {
                            @Override
                            public Observable<?> call(Integer attempt) {
                                return Observable.timer(delay.calculate(attempt), delay.unit());
                            }
                        });
                    }
                })
                //ignore intermediate states as long as they don't match the criteria
                .skipWhile(new Func1<ObserveItem, Boolean>() {
                    @Override
                    public Boolean call(ObserveItem status) {
                        return !status.check(persistTo, replicateTo);
                    }
                })
                //finish as soon as the first poll that matches the whole criteria is encountered
                .take(1)
                .map(new Func1<ObserveItem, Boolean>() {
                    @Override
                    public Boolean call(ObserveItem observeResponses) {
                        return true;
                    }
                });
    }

    private static Observable<ObserveResponse> sendObserveRequests(final ClusterFacade core, final String bucket,
        final String id, final long cas, final Observe.PersistTo persistTo, final Observe.ReplicateTo replicateTo,
        final RetryStrategy retryStrategy) {
        final boolean swallowErrors = retryStrategy.shouldRetryObserve();
        return Observable.defer(new Func0<Observable<ObserveResponse>>() {
            @Override
            public Observable<ObserveResponse> call() {
                return core
                        .<GetClusterConfigResponse>send(new GetClusterConfigRequest())
                        .map(new Func1<GetClusterConfigResponse, Integer>() {
                            @Override
                            public Integer call(GetClusterConfigResponse response) {
                                CouchbaseBucketConfig conf =
                                        (CouchbaseBucketConfig) response.config().bucketConfig(bucket);
                                int numReplicas = conf.numberOfReplicas();

                                if (replicateTo.touchesReplica() && replicateTo.value() > numReplicas) {
                                    throw new ReplicaNotConfiguredException("Not enough replicas configured on " +
                                            "the bucket.");
                                }
                                if (persistTo.touchesReplica() && persistTo.value() - 1 > numReplicas) {
                                    throw new ReplicaNotConfiguredException("Not enough replicas configured on " +
                                            "the bucket.");
                                }
                                return numReplicas;
                            }
                        })
                        .flatMap(new Func1<Integer, Observable<ObserveResponse>>() {
                            @Override
                            public Observable<ObserveResponse> call(Integer replicas) {
                                List<Observable<ObserveResponse>> obs = new ArrayList<Observable<ObserveResponse>>();
                                Observable<ObserveResponse> masterRes = core.send(new ObserveRequest(id, cas, true, (short) 0, bucket));
                                if (swallowErrors) {
                                    obs.add(masterRes.onErrorResumeNext(Observable.<ObserveResponse>empty()));
                                } else {
                                    obs.add(masterRes);
                                }

                                if (persistTo.touchesReplica() || replicateTo.touchesReplica()) {
                                    for (short i = 1; i <= replicas; i++) {
                                        Observable<ObserveResponse> res = core.send(new ObserveRequest(id, cas, false, i, bucket));
                                        if (swallowErrors) {
                                            obs.add(res.onErrorResumeNext(Observable.<ObserveResponse>empty()));
                                        } else {
                                            obs.add(res);
                                        }
                                    }
                                }

                                if (obs.size() == 1) {
                                    return obs.get(0);
                                } else {
                                    //mergeDelayErrors will give a chance to other nodes to respond (maybe with enough
                                    //responses for the whole poll to be considered a success)
                                    return Observable.mergeDelayError(Observable.from(obs));
                                }
                            }
                        });
            }
        });
    }

    /**
     * An immutable state class that can be constructed from an {@link ObserveResponse}
     * and aggregated with other intermediary states during a {@link Observable#scan(Func2) scan}.
     *
     * This encapsulates the logic of tracking observed state and checking it against a
     * {@link Observe.ReplicateTo replication} or {@link Observe.PersistTo persistence} criteria.
     *
     * Having each intermediate state will allow to shortcut as soon as the desired state is
     * observed, not until all the replicas have manifested themselves.
     */
    private static class ObserveItem {

        private final int replicated;
        private final int persisted;
        private final boolean persistedMaster;


        /**
         * Build an {@link ObserveItem} state from a {@link ObserveResponse}.
         *
         * @param id the observed key.
         * @param response the {@link ObserveResponse} received for that key.
         * @param cas the cas that we expect.
         * @param remove true if this is a remove operation, false otherwise.
         * @param persistIdentifier the {@link ObserveResponse.ObserveStatus} to watch for in persistence.
         * @param replicaIdentifier the {@link ObserveResponse.ObserveStatus} to watch for in replication.
         * @throws DocumentConcurrentlyModifiedException if the cas observed on master copy isn't the expected one.
         */
        public ObserveItem(String id, ObserveResponse response, long cas, boolean remove,
                           ObserveResponse.ObserveStatus persistIdentifier,
                           ObserveResponse.ObserveStatus replicaIdentifier) {
            int replicated = 0;
            int persisted = 0;
            boolean persistedMaster = false;


            if (response.content() != null && response.content().refCnt() > 0) {
                response.content().release();
            }
            ObserveResponse.ObserveStatus status = response.observeStatus();

            // the CAS values always need to match up to make sure we are still observing the right
            // document. The only exclusion from that rule is when a real delete is returned, because
            // then the cas value is 0.
            boolean validCas = cas == response.cas()
                    || (remove && response.cas() == 0 && status == persistIdentifier);

            if (response.master()) {
                if (!validCas) {
                    throw new DocumentConcurrentlyModifiedException("The CAS on the active node "
                            + "changed for ID \"" + id + "\", indicating it has been modified in the "
                            + "meantime.");
                }

                if (status == persistIdentifier) {
                    persisted++;
                    persistedMaster = true;
                }
            } else if (validCas) {
                if (status == persistIdentifier) {
                    persisted++;
                    replicated++;
                } else if (status == replicaIdentifier) {
                    replicated++;
                }
            }

            this.replicated = replicated;
            this.persisted = persisted;
            this.persistedMaster = persistedMaster;
        }


        private ObserveItem(int replicated, int persisted, boolean persistedMaster) {
            this.replicated = replicated;
            this.persisted = persisted;
            this.persistedMaster = persistedMaster;
        }

        /**
         * An empty {@link ObserveViaCAS.ObserveItem}, when nothing has been observed yet.
         */
        public ObserveItem() {
            this(0, 0, false);
        }

        /**
         * Aggregate two ObserveItems together, merging the state they represent.
         *
         * @param other the other ObserveItem to aggregate with.
         * @return a new {@link ObserveItem} representing the aggregated state of this and the other state.
         */
        public ObserveItem add(ObserveItem other) {
            return new ObserveItem(this.replicated + other.replicated,
                    this.persisted + other.persisted,
                    this.persistedMaster || other.persistedMaster);
        }

        /**
         * Checks the state to see if it matches the given criteria.
         *
         * @param persistTo minimum number of persistence to be observed for this to match.
         * @param replicateTo minimum number of replications to be observed for this to match.
         * @return true if the current state matches the given criteria.
         */
        public boolean check(Observe.PersistTo persistTo, Observe.ReplicateTo replicateTo) {
            boolean persistDone = false;
            boolean replicateDone = false;

            if (persistTo == Observe.PersistTo.MASTER) {
                if (persistedMaster) {
                    persistDone = true;
                }
            } else if (persisted >= persistTo.value()) {
                persistDone = true;
            }

            if (replicated >= replicateTo.value()) {
                replicateDone = true;
            }

            return persistDone && replicateDone;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("persisted ").append(persisted);
            if (persistedMaster)
                sb.append(" (master)");
            sb.append(", replicated ").append(replicated);
            return sb.toString();
        }
    }
}
