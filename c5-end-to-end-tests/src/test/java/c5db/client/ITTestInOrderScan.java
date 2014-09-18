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
package c5db.client;

import c5db.ClusterOrPseudoCluster;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static c5db.client.DataHelper.putRowInDB;
import static junit.framework.TestCase.assertFalse;

@Ignore
public class ITTestInOrderScan extends ClusterOrPseudoCluster {

  private final byte[] cf = Bytes.toBytes("cf");

  @Test
  public void testInOrderScan() throws IOException, InterruptedException, TimeoutException, ExecutionException {
    Result result = null;
    ResultScanner scanner;
    putRowInDB(table, row);
    row = Bytes.add(row, row);
    putRowInDB(table, row);
    row = Bytes.add(row, row);
    putRowInDB(table, row);
    row = Bytes.add(row, row);
    putRowInDB(table, row);
    row = Bytes.add(row, row);
    putRowInDB(table, row);
    row = Bytes.add(row, row);
    putRowInDB(table, row);

    scanner = table.getScanner(cf);
    byte[] previousRow = {};
    do {
      if (result != null) {
        previousRow = result.getRow();
      }
      result = scanner.next();
      if (result != null) assertFalse(Bytes.compareTo(result.getRow(), previousRow) < 1);
    } while (result != null);
  }
}
