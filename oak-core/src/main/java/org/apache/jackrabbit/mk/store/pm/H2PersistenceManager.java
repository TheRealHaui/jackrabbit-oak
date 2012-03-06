/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.mk.store.pm;

import org.apache.jackrabbit.mk.model.ChildNodeEntriesMap;
import org.apache.jackrabbit.mk.model.Commit;
import org.apache.jackrabbit.mk.model.Node;
import org.apache.jackrabbit.mk.model.StoredCommit;
import org.apache.jackrabbit.mk.store.BinaryBinding;
import org.apache.jackrabbit.mk.store.Binding;
import org.apache.jackrabbit.mk.store.IdFactory;
import org.apache.jackrabbit.mk.store.NotFoundException;
import org.apache.jackrabbit.mk.util.StringUtils;
import org.h2.jdbcx.JdbcConnectionPool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 *
 */
public class H2PersistenceManager implements PersistenceManager {

    private static final boolean FAST = Boolean.getBoolean("mk.fastDb");

    private JdbcConnectionPool cp;
    
    // TODO: make this configurable
    private IdFactory idFactory = IdFactory.getDigestFactory();

    //---------------------------------------------------< PersistenceManager >

    public void initialize(File homeDir) throws Exception {
        File dbDir = new File(homeDir, "db");
        if (!dbDir.exists()) {
            dbDir.mkdir();
        }

        Class.forName("org.h2.Driver");
        String url = "jdbc:h2:" + dbDir.getCanonicalPath() + "/revs";
        if (FAST) {
            url += ";log=0;undo_log=0";
        }
        cp = JdbcConnectionPool.create(url, "sa", "");
        cp.setMaxConnections(40);
        Connection con = cp.getConnection();
        try {
            Statement stmt = con.createStatement();
            stmt.execute("create table if not exists REVS (ID binary primary key, DATA binary)");
            stmt.execute("create table if not exists head(id varchar) as select ''");
            stmt.execute("create sequence if not exists datastore_id");
/*
            DbBlobStore store = new DbBlobStore();
            store.setConnectionPool(cp);
            blobStore = store;
*/
        } finally {
            con.close();
        }
    }

    public void close() {
        cp.dispose();
    }

    public String readHead() throws Exception {
        Connection con = cp.getConnection();
        try {
            PreparedStatement stmt = con.prepareStatement("select * from head");
            ResultSet rs = stmt.executeQuery();
            String headId = null;
            if (rs.next()) {
                headId = rs.getString(1);
            }
            stmt.close();
            return headId;
        } finally {
            con.close();
        }
    }

    public void writeHead(String id) throws Exception {
        Connection con = cp.getConnection();
        try {
            PreparedStatement stmt = con.prepareStatement("update head set id=?");
            stmt.setString(1, id);
            stmt.execute();
            stmt.close();
        } finally {
            con.close();
        }
    }

    public Binding readNodeBinding(String id) throws NotFoundException, Exception {
        Connection con = cp.getConnection();
        try {
            PreparedStatement stmt = con.prepareStatement("select DATA from REVS where ID = ?");
            try {
                stmt.setBytes(1, StringUtils.convertHexToBytes(id));
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    ByteArrayInputStream in = new ByteArrayInputStream(rs.getBytes(1));
                    return new BinaryBinding(in);
                } else {
                    throw new NotFoundException(id);
                }
            } finally {
                stmt.close();
            }
        } finally {
            con.close();
        }
    }

    public String writeNode(Node node) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        node.serialize(new BinaryBinding(out));
        byte[] bytes = out.toByteArray();
        byte[] rawId = idFactory.createContentId(bytes);
        String id = StringUtils.convertBytesToHex(rawId);

        Connection con = cp.getConnection();
        try {
            PreparedStatement stmt = con
                    .prepareStatement(
                            "insert into REVS (ID, DATA) select ?, ? where not exists (select 1 from revs where ID = ?)");
            try {
                stmt.setBytes(1, rawId);
                stmt.setBytes(2, bytes);
                stmt.setBytes(3, rawId);
                stmt.executeUpdate();
            } finally {
                stmt.close();
            }
        } finally {
            con.close();
        }
        return id;
    }

    public StoredCommit readCommit(String id) throws NotFoundException, Exception {
        Connection con = cp.getConnection();
        try {
            PreparedStatement stmt = con.prepareStatement("select DATA from REVS where ID = ?");
            try {
                stmt.setBytes(1, StringUtils.convertHexToBytes(id));
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    ByteArrayInputStream in = new ByteArrayInputStream(rs.getBytes(1));
                    return StoredCommit.deserialize(id, new BinaryBinding(in));
                } else {
                    throw new NotFoundException(id);
                }
            } finally {
                stmt.close();
            }
        } finally {
            con.close();
        }
    }
    
    public void writeCommit(byte[] rawId, Commit commit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        commit.serialize(new BinaryBinding(out));
        byte[] bytes = out.toByteArray();

        Connection con = cp.getConnection();
        try {
            PreparedStatement stmt = con
                    .prepareStatement(
                            "insert into REVS (ID, DATA) select ?, ? where not exists (select 1 from revs where ID = ?)");
            try {
                stmt.setBytes(1, rawId);
                stmt.setBytes(2, bytes);
                stmt.setBytes(3, rawId);
                stmt.executeUpdate();
            } finally {
                stmt.close();
            }
        } finally {
            con.close();
        }
    }

    public ChildNodeEntriesMap readCNEMap(String id) throws NotFoundException, Exception {
        Connection con = cp.getConnection();
        try {
            PreparedStatement stmt = con.prepareStatement("select DATA from REVS where ID = ?");
            try {
                stmt.setBytes(1, StringUtils.convertHexToBytes(id));
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    ByteArrayInputStream in = new ByteArrayInputStream(rs.getBytes(1));
                    return ChildNodeEntriesMap.deserialize(new BinaryBinding(in));
                } else {
                    throw new NotFoundException(id);
                }
            } finally {
                stmt.close();
            }
        } finally {
            con.close();
        }
    }

    public String writeCNEMap(ChildNodeEntriesMap map) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        map.serialize(new BinaryBinding(out));
        byte[] bytes = out.toByteArray();
        byte[] rawId = idFactory.createContentId(bytes);
        String id = StringUtils.convertBytesToHex(rawId);

        Connection con = cp.getConnection();
        try {
            PreparedStatement stmt = con
                    .prepareStatement(
                            "insert into REVS (ID, DATA) select ?, ? where not exists (select 1 from revs where ID = ?)");
            try {
                stmt.setBytes(1, rawId);
                stmt.setBytes(2, bytes);
                stmt.setBytes(3, rawId);
                stmt.executeUpdate();
            } finally {
                stmt.close();
            }
        } finally {
            con.close();
        }
        return id;
    }
}