/*
 * Copyright 2018 SJTU IST Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.edu.sjtu.ist.ops;

import cn.edu.sjtu.ist.ops.common.JobConf;
import cn.edu.sjtu.ist.ops.common.OpsConf;
import cn.edu.sjtu.ist.ops.common.OpsNode;
import cn.edu.sjtu.ist.ops.common.TaskConf;
import cn.edu.sjtu.ist.ops.common.TaskPreAlloc;
import cn.edu.sjtu.ist.ops.common.ShuffleConf;
import com.google.gson.Gson;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.io.File;
import com.google.common.io.Files;
import com.google.common.io.FileWriteMode;
import com.google.common.io.ByteSink;

public class OpsShuffleHandler extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(OpsShuffleHandler.class);
    private final Server server;
    private volatile boolean stopped;
    private final OpsConf opsConf;
    private Set<ShuffleConf> pendingShuffles = new HashSet<>();
    private HashMap<String, JobConf> jobs;
    private final Random random = new Random();

    private final ManagedChannel channel;
    private final OpsInternalGrpc.OpsInternalStub asyncStub;

    public OpsShuffleHandler(OpsConf opsConf) {
        stopped = false;
        this.opsConf = opsConf;
        this.jobs = new HashMap<>();

        this.channel = ManagedChannelBuilder.forAddress(opsConf.getMaster().getIp(), opsConf.getPortMasterGRPC())
                .usePlaintext().build();
        this.asyncStub = OpsInternalGrpc.newStub(channel);

        this.server = ServerBuilder.forPort(this.opsConf.getPortWorkerGRPC()).addService(new OpsInternalService())
                .build();
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        this.setName("ops-shuffle-handler");
        try {
            this.server.start();
            logger.info("gRPC Server started, listening on " + this.opsConf.getPortWorkerGRPC());

            // while (!stopped && !Thread.currentThread().isInterrupted()) {

            // }
            server.awaitTermination();
            // channel.wait();
        } catch (Exception e) {
            // TODO: handle exception
        }
    }

    public synchronized ShuffleConf getPendingShuffle() throws InterruptedException {
        while (pendingShuffles.isEmpty()) {
            wait();
        }

        ShuffleConf shuffle = null;
        Iterator<ShuffleConf> iter = pendingShuffles.iterator();
        int numToPick = random.nextInt(pendingShuffles.size());
        for (int i = 0; i <= numToPick; ++i) {
            shuffle = iter.next();
        }

        pendingShuffles.remove(shuffle);

        logger.debug("Shuffle " + shuffle.toString());
        return shuffle;
    }

    public JobConf getJob(String jobId) {
        return this.jobs.get(jobId);
    }

    public synchronized void addpendingShuffles(ShuffleConf shuffle) {
        pendingShuffles.add(shuffle);
        logger.debug("onShuffle: add pendingShuffles task " + shuffle.getTask().getTaskId() + " to node "
                + shuffle.getDstNode().getIp());
        notifyAll();
    }

    public void taskComplete(TaskConf task) {
        StreamObserver<TaskMessage> requestObserver = asyncStub.onTaskComplete(new StreamObserver<TaskMessage>() {
            @Override
            public void onNext(TaskMessage msg) {
                logger.debug("ShuffleHandler: " + msg.getTaskConf());
            }

            @Override
            public void onError(Throwable t) {

            }

            @Override
            public void onCompleted() {

            }
        });

        try {
            Gson gson = new Gson();
            TaskMessage message = TaskMessage.newBuilder().setTaskConf(gson.toJson(task)).build();
            requestObserver.onNext(message);
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }
        // Mark the end of requests
        requestObserver.onCompleted();
    }

    private class OpsInternalService extends OpsInternalGrpc.OpsInternalImplBase {
        @Override
        public StreamObserver<Chunk> transfer(StreamObserver<StatusMessage> responseObserver) {
            return new StreamObserver<Chunk>() {
                @Override
                public void onNext(Chunk chunk) {
                    try {
                        File file = new File("/Users/admin/Documents/GitHub/OPS/file.receive");
                        if (!file.exists()) {
                            file.createNewFile();
                        }
                        ByteSink byteSink = Files.asByteSink(file, FileWriteMode.APPEND);
                        byteSink.write(chunk.getContent().toByteArray());
                        logger.debug("Receive chunk, file length: " + file.length());
                    } catch (Exception e) {
                        e.printStackTrace();
                        // TODO: handle exception
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.warn("Encountered error in exchange", t);
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<ShuffleMessage> onShuffle(StreamObserver<ShuffleMessage> responseObserver) {
            return new StreamObserver<ShuffleMessage>() {
                @Override
                public void onNext(ShuffleMessage request) {
                    // responseObserver.onNext(ShuffleMessage.newBuilder().setMsg("ShuffleMessage").build());

                    Gson gson = new Gson();
                    TaskConf task = gson.fromJson(request.getTaskConf(), TaskConf.class);
                    if (!jobs.containsKey(task.getJobId())) {
                        logger.error("JobId not found: " + task.getJobId());
                        return;
                    }
                    JobConf job = jobs.get(task.getJobId());
                    TaskPreAlloc preAlloc = job.getReducePreAlloc();
                    for (OpsNode node : preAlloc.getNodesMap().values()) {
                        ShuffleConf shuffle = new ShuffleConf(task, node, preAlloc.getTaskOrder(node.getIp()));
                        addpendingShuffles(shuffle);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.warn("Encountered error in exchange", t);
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<JobMessage> distributeJob(StreamObserver<JobMessage> responseObserver) {
            return new StreamObserver<JobMessage>() {
                @Override
                public void onNext(JobMessage request) {
                    Gson gson = new Gson();
                    JobConf job = gson.fromJson(request.getJobConf(), JobConf.class);
                    jobs.put(job.getJobId(), job);
                    logger.info("Get job: " + job.toString());
                }

                @Override
                public void onError(Throwable t) {
                    logger.warn("Encountered error in exchange", t);
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}