/*
 * Copyright 2014 WANdisco
 *
 *  WANdisco licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package c5db.regionserver;


import c5db.client.generated.Call;
import c5db.client.generated.Response;
import c5db.client.generated.Result;
import c5db.client.generated.ScanResponse;
import c5db.tablet.Region;
import io.netty.channel.ChannelHandlerContext;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.jetlang.core.Callback;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates a runnable in the background so that the regionserver always has a setup of scanner results
 * ready to send back to the user. It directly sends the data back through netty back to the user.
 */
public class ScanRunnable implements Callback<Integer> {
  private final long scannerId;
  private final Call call;
  private final ChannelHandlerContext ctx;
  private final RegionScanner scanner;
  private boolean close;

  public ScanRunnable(final ChannelHandlerContext ctx,
                      final Call call,
                      final long scannerId,
                      final Region region) throws IOException {
    super();
    assert (call.getScan() != null);

    this.ctx = ctx;
    this.call = call;
    this.scannerId = scannerId;
    this.scanner = region.getScanner(call.getScan().getScan());
    this.close = false;
  }

  @Override
  public void onMessage(Integer numberOfMessagesToSend) {
    if (this.close) {
      return;
    }
    long numberOfMsgsLeft = numberOfMessagesToSend;
    List<Result> scanResults = new ArrayList<>();
    List<Integer> cellsPerResult = new ArrayList<>();
    ByteBuffer previousRow = null;
    while (!this.close && numberOfMsgsLeft > 0) {
      int rowsToSend = 0;
      boolean moreResults;
      do {
        List<Cell> rawCells = new ArrayList<>();

        try {
          // Arguably you should only return numberOfMessages, but I figure it can't hurt that
          // much to pass them up
          moreResults = scanner.nextRaw(rawCells);
          if (!moreResults) {
            this.scanner.close();
            this.close = true;
          }
        } catch (IOException e) {
          e.printStackTrace();
          return;
        }

        List<c5db.client.generated.Cell> cells = new ArrayList<>();
        for (Cell cell : rawCells) {
          ByteBuffer cellBufferRow = ByteBuffer.wrap(cell.getRowArray(), cell.getRowOffset(), cell.getRowLength());
          // If we are not the first one and we are a different row than the previous
          cells.add(ReverseProtobufUtil.toCell(cell));

          if (!(previousRow == null || previousRow.compareTo(cellBufferRow) == 0)) {
            cellsPerResult.add(cells.size());
            scanResults.add(new Result(cells, cells.size(), cells.size() > 0));
            cells = new ArrayList<>();
          }
          previousRow = ByteBuffer.wrap(cell.getRowArray(), cell.getRowOffset(), cell.getRowLength());
        }
        // Add the last one
        if (cells.size() > 0) {
          cellsPerResult.add(cells.size());
          scanResults.add(new Result(cells, cells.size(), cells.size() > 0));
        }
        rowsToSend++;
        // Our super advanced scanning algorithm. Could be greatly improved
      } while (moreResults && rowsToSend < 100 && numberOfMessagesToSend - rowsToSend > 0);
      ScanResponse scanResponse = new ScanResponse(cellsPerResult, scannerId, moreResults, 0, scanResults);
      Response response = new Response(Response.Command.SCAN, call.getCommandId(), null, null, scanResponse, null);
      ctx.writeAndFlush(response);
      numberOfMsgsLeft -= rowsToSend;
    }
  }

}


