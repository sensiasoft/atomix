/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft;

import io.atomix.protocols.raft.cluster.MemberId;
import io.atomix.protocols.raft.proxy.RaftProxy;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.MoreObjects.toStringHelper;

/**
 * Raft client delegate.
 */
public class RaftClientDelegate implements RaftClient {
  private final RaftClient delegate;

  public RaftClientDelegate(RaftClient delegate) {
    this.delegate = delegate;
  }

  @Override
  public String id() {
    return delegate.id();
  }

  @Override
  public RaftMetadataClient metadata() {
    return delegate.metadata();
  }

  @Override
  public RaftProxy.Builder proxyBuilder() {
    return delegate.proxyBuilder();
  }

  @Override
  public CompletableFuture<RaftClient> connect(Collection<MemberId> members) {
    return delegate.connect(members);
  }

  @Override
  public CompletableFuture<Void> close() {
    return delegate.close();
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("delegate", delegate)
        .toString();
  }
}