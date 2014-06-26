/*
 * Copyright (C) 2013  Ohm Data
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/** Incorporates changes licensed under:
 *
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

package c5db.client;

import c5db.client.generated.Action;
import c5db.client.generated.Condition;
import c5db.client.generated.GetRequest;
import c5db.client.generated.MutateRequest;
import c5db.client.generated.MutationProto;
import c5db.client.generated.RegionAction;
import c5db.client.generated.RegionSpecifier;
import c5db.client.generated.TableName;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A utility class responsible for generating requests to c5.
 */
public final class RequestConverter {

  private RequestConverter() {
    throw new UnsupportedOperationException();
  }

  /**
   * Create a protocol buffer GetRequest for a client Get.
   *
   * @param get           the client Get
   * @param existenceOnly indicate if check row existence only
   * @return a protocol buffer GetRequest
   */
  public static GetRequest buildGetRequest(final Get get,
                                           final boolean existenceOnly) throws IOException {
    RegionSpecifier regionSpecifier = new RegionSpecifier();
    return new GetRequest(regionSpecifier, ProtobufUtil.toGet(get, existenceOnly));
  }

  /**
   * Create a protocol buffer MutateRequest for a put.
   *
   * @param mutation The mutation to process
   * @param type     The type of mutation to process
   * @return a mutate request
   */
  public static MutateRequest buildMutateRequest(final MutationProto.MutationType type,
                                                 final Mutation mutation) {
    final RegionSpecifier region = new RegionSpecifier();
    return new MutateRequest(region, ProtobufUtil.toMutation(type, mutation), new Condition());
  }

  /**
   * Create a protocol buffer MutateRequest for a put.
   *
   * @param mutation  The mutation to process
   * @param type      The type of mutation to process
   * @param condition The optional condition or null
   * @return a mutate request
   */
  public static MutateRequest buildMutateRequest(final MutationProto.MutationType type,
                                                 final Mutation mutation,
                                                 final Condition condition) {
    final RegionSpecifier region = new RegionSpecifier();
    return new MutateRequest(region,
        ProtobufUtil.toMutation(type, mutation),
        condition);
  }

  /**
   * Create a protocol buffer MultiRequest for row mutations.
   * Does not propagate Action absolute position.
   *
   * @param rowMutations The row mutations to apply to the region
   * @return a data-laden RegionAction
   */
  public static RegionAction buildRegionAction(final RowMutations rowMutations)
      throws IOException {
    RegionSpecifier regionSpecifier = new RegionSpecifier();
    final List<Action> actions = new ArrayList<>();
    int index = 0;
    for (Mutation mutation : rowMutations.getMutations()) {
      MutationProto.MutationType mutateType;
      if (mutation instanceof Put) {
        mutateType = MutationProto.MutationType.PUT;
      } else if (mutation instanceof Delete) {
        mutateType = MutationProto.MutationType.DELETE;
      } else {
        throw new DoNotRetryIOException("RowMutations supports only put and delete, not "
            + mutation.getClass().getName());
      }
      final MutationProto mp = ProtobufUtil.toMutation(mutateType, mutation);
      final Action action = new Action(++index, mp, new c5db.client.generated.Get());
      actions.add(action);

    }
    return new RegionAction(regionSpecifier, true, actions);
  }

  /**
   * Create a protocol buffer multi request for a list of actions.
   * Propagates Actions original index.
   *
   * @param actionsIn
   * @return a multi request
   * @throws IOException
   */
  public static <R> RegionAction buildRegionAction(final List<? extends Row> actionsIn)
      throws IOException {
    final RegionSpecifier region = new RegionSpecifier();
    List<Action> actions = new ArrayList<>();
    int index = 0;
    for (Row row : actionsIn) {
      Action action;
      if (row instanceof Get) {
        Get g = (Get) row;
        action = new Action(index, null, ProtobufUtil.toGet(g, false));
      } else if (row instanceof Put) {
        Put p = (Put) row;
        action = new Action(index, ProtobufUtil.toMutation(MutationProto.MutationType.PUT, p), null);
      } else if (row instanceof Delete) {
        Delete d = (Delete) row;
        action = new Action(index, ProtobufUtil.toMutation(MutationProto.MutationType.DELETE, d), null);
      } else {
        throw new DoNotRetryIOException("Multi doesn't support " + row.getClass().getName());
      }
      actions.add(action);


    }
    return new RegionAction(region, true, actions);
  }

  public static byte[] buildRegionName(TableName tableName, byte[] row) {

    byte[] fullTableName = Bytes.add(tableName.getNamespace().array(),
        Bytes.toBytes(":"),
        tableName.getQualifier().array());

    return Bytes.add(fullTableName, Bytes.toBytes(","), row);
  }

  public static byte[] buildScannerRegionName(TableName tableName, Scan scan) {
    if (scan.getStartRow() == null || scan.getStartRow().length == 0) {
      return buildRegionName(tableName, new byte[]{0x00});
    } else {
      return buildRegionName(tableName, scan.getStartRow());
    }
  }
}
