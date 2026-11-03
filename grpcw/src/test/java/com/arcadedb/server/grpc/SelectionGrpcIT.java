/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
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
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.server.grpc;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.graph.MutableVertex;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 slice 4c (docs/spork/03-f1-selections-design.md): the gRPC surface of saved selections - eight RPCs over the same
 * {@code SelectionService} the HTTP routes and the MCP tools call - and the {@code selection} field of the search RPCs.
 * Fixture: G1 -> P1, G2 -> P1 and P2, G3 -> P2; every ad's text mentions "escort".
 */
public class SelectionGrpcIT extends BaseGrpcServerTest {
  private static final Metadata.Key<String> USER_HEADER     = Metadata.Key.of("x-arcade-user", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> PASSWORD_HEADER = Metadata.Key.of("x-arcade-password",
      Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> DATABASE_HEADER = Metadata.Key.of("x-arcade-database",
      Metadata.ASCII_STRING_MARSHALLER);

  private final List<String> ads = new ArrayList<>();

  private ManagedChannel                                  channel;
  private ArcadeDbServiceGrpc.ArcadeDbServiceBlockingStub stub;

  private class AuthClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(final MethodDescriptor<ReqT, RespT> method,
        final CallOptions callOptions, final Channel next) {
      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override
        public void start(final Listener<RespT> responseListener, final Metadata headers) {
          headers.put(USER_HEADER, "root");
          headers.put(PASSWORD_HEADER, DEFAULT_PASSWORD_FOR_TESTS);
          headers.put(DATABASE_HEADER, getDatabaseName());
          super.start(responseListener, headers);
        }
      };
    }
  }

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.SERVER_PLUGINS.setValue("GRPC:com.arcadedb.server.grpc.GrpcServerPlugin");
  }

  @Override
  protected void populateDatabase() {
    super.populateDatabase();

    final Database db = getDatabase(0);
    db.getSchema().createVertexType("GrpcAd");
    db.getSchema().createVertexType("GrpcPhone");
    db.getSchema().createEdgeType("GRPC_PHONE");
    db.command("sql", "CREATE PROPERTY GrpcAd.text STRING");
    db.command("sql", "CREATE INDEX ON GrpcAd (text) FULL_TEXT");
    db.transaction(() -> {
      final MutableVertex p1 = db.newVertex("GrpcPhone").set("name", "P1").save();
      final MutableVertex p2 = db.newVertex("GrpcPhone").set("name", "P2").save();
      for (int i = 1; i <= 3; i++) {
        final MutableVertex ad = db.newVertex("GrpcAd").set("name", "G" + i).set("text", "escort ad " + i).save();
        if (i <= 2)
          ad.newEdge("GRPC_PHONE", p1).save();
        if (i >= 2)
          ad.newEdge("GRPC_PHONE", p2).save();
        ads.add(ad.getIdentity().toString());
      }
    });
  }

  @BeforeEach
  void setupGrpcClient() {
    channel = ManagedChannelBuilder.forAddress("localhost", getServerGrpcPort()).usePlaintext().build();
    stub = ArcadeDbServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, new AuthClientInterceptor()));
  }

  @AfterEach
  void teardownGrpcClient() throws InterruptedException {
    if (channel != null) {
      channel.shutdown();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void theWholeLifecycleOverGrpc() {
    final SelectionHandle left = stub.saveSelection(SaveSelectionRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setName("left").setMembers(rids(ads.get(0))).build());
    assertThat(left.getCardinality()).isEqualTo(1L);
    assertThat(left.hasExpiresAt()).isTrue();

    stub.saveSelection(SaveSelectionRequest.newBuilder().setDatabase(getDatabaseName()).setCredentials(root()).setName("right")
        .setQuery("SELECT FROM GrpcAd WHERE name IN ['G2', 'G3']").setLanguage("sql").setTtlSeconds(0).build());

    final CompareSelectionsResponse compared = stub.compareSelections(CompareSelectionsRequest.newBuilder()
        .setDatabase(getDatabaseName()).setCredentials(root()).addSelections(named("left")).addSelections(named("right"))
        .addVia("GRPC_PHONE").setDirection("OUT").build());
    assertThat(compared.getCount()).isEqualTo(1);
    final SharedElement shared = compared.getRows(0);
    assertThat(shared.getRecord().getPropertiesMap().get("name").getStringValue()).isEqualTo("P1");
    assertThat(shared.getSharedBy()).isEqualTo(2);
    assertThat(shared.getCountsList()).containsExactly(1L, 1L);
    assertThat(shared.getMembers(1).getRidsList()).containsExactly(ads.get(1));

    final SelectionVennResponse venn = stub.selectionVenn(SelectionVennRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).addSelections(named("left")).addSelections(named("right")).build());
    assertThat(venn.getRegionsCount()).isEqualTo(2);

    final SelectionHandle right = stub.getSelection(GetSelectionRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setName("right").build());
    assertThat(right.getLanguage()).isEqualTo("sql");
    assertThat(right.hasExpiresAt()).isFalse();

    final SelectionMembersResponse members = stub.selectionMembers(SelectionMembersRequest.newBuilder()
        .setDatabase(getDatabaseName()).setCredentials(root()).setName("right").build());
    assertThat(members.getCount()).isEqualTo(2);
    // in RID order, which across the type's buckets need not be insertion order
    final List<String> memberNames = new ArrayList<>();
    for (final SharedElement member : members.getMembersList())
      memberNames.add(member.getRecord().getPropertiesMap().get("name").getStringValue());
    assertThat(memberNames).containsExactlyInAnyOrder("G2", "G3");

    assertThat(stub.combineSelections(CombineSelectionsRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setOp("or").addInputs(named("left")).addInputs(named("right")).setName("all").build())
        .getCardinality()).isEqualTo(3L);
    assertThat(stub.dropSelection(DropSelectionRequest.newBuilder().setDatabase(getDatabaseName()).setCredentials(root())
        .setName("all").build()).getDropped()).isTrue();
    assertThat(stub.purgeSelections(PurgeSelectionsRequest.newBuilder().setDatabase(getDatabaseName()).setCredentials(root())
        .build()).getPurged()).isZero();
  }

  @Test
  void theBitmapTravelsAsBytes() {
    stub.saveSelection(SaveSelectionRequest.newBuilder().setDatabase(getDatabaseName()).setCredentials(root()).setName("two")
        .setMembers(rids(ads.get(0), ads.get(2))).build());
    final SelectionHandle exported = stub.getSelection(GetSelectionRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setName("two").setBitmap(true).build());
    assertThat(exported.getBitmap().isEmpty()).isFalse();

    final SelectionHandle copy = stub.saveSelection(SaveSelectionRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setName("copy").setBitmap(exported.getBitmap()).build());
    assertThat(copy.getCardinality()).isEqualTo(2L);
  }

  @Test
  void theSearchRpcsTakeASelection() {
    stub.saveSelection(SaveSelectionRequest.newBuilder().setDatabase(getDatabaseName()).setCredentials(root()).setName("two")
        .setMembers(rids(ads.get(0), ads.get(2))).build());

    final FullTextSearchResponse hits = stub.fullTextSearch(FullTextSearchRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setIndexName("GrpcAd[text]").setQueryText("escort").setSelection("two").build());
    final List<String> names = new ArrayList<>();
    for (final SearchHit hit : hits.getResultsList())
      names.add(hit.getRecord().getPropertiesMap().get("name").getStringValue());
    assertThat(names).containsExactlyInAnyOrder("G1", "G3");
  }

  @Test
  void aRefusalIsAnInvalidArgument() {
    assertThatThrownBy(() -> stub.getSelection(GetSelectionRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setName("nope").build()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));

    assertThatThrownBy(() -> stub.saveSelection(SaveSelectionRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setName("nothing").build()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  @Test
  void aTakenNameIsAlreadyExists() {
    stub.saveSelection(SaveSelectionRequest.newBuilder().setDatabase(getDatabaseName()).setCredentials(root()).setName("taken")
        .setMembers(rids(ads.get(0))).build());
    assertThatThrownBy(() -> stub.saveSelection(SaveSelectionRequest.newBuilder().setDatabase(getDatabaseName())
        .setCredentials(root()).setName("taken").setMembers(rids(ads.get(1))).build()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS));
  }

  private static SelectionRef named(final String name) {
    return SelectionRef.newBuilder().setName(name).build();
  }

  private static RidList rids(final String... rids) {
    return RidList.newBuilder().addAllRids(List.of(rids)).build();
  }

  private DatabaseCredentials root() {
    return DatabaseCredentials.newBuilder().setUsername("root").setPassword(DEFAULT_PASSWORD_FOR_TESTS).build();
  }
}
