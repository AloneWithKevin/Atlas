package nl.pixelretreat.atlas.storage;

import java.sql.*;

/** Startup-only widening of owned identity columns. Never renames historical identities. */
public final class ServerIdentityColumns {
    private ServerIdentityColumns() {}
    public static void widen(Connection connection,String table,String... columns)throws SQLException {
        if(!table.matches("atlas_[a-z_]+"))throw new IllegalArgumentException("Invalid owned table");
        for(String column:columns) {
            if(!column.matches("[a-z_]+"))throw new IllegalArgumentException("Invalid owned column");
            long length;String collation;boolean nullable;
            try(var q=connection.prepareStatement("SELECT CHARACTER_MAXIMUM_LENGTH,COLLATION_NAME,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?")) {
                q.setString(1,table);q.setString(2,column);
                try(var r=q.executeQuery()) {
                    if(!r.next())throw new SQLException("Missing owned identity column");
                    length=r.getLong(1);collation=r.getString(2);nullable=r.getString(3).equals("YES");
                }
            }
            if(length==64 && "ascii_bin".equals(collation))continue;
            // A foreign historical value stops the migration instead of being folded/truncated.
            try(var q=connection.createStatement();var r=q.executeQuery("SELECT 1 FROM `"+table+"` WHERE `"+column+"` IS NOT NULL AND BINARY `"+column+"` NOT REGEXP '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' LIMIT 1")) {
                if(r.next())throw new SQLException("Historical server identity requires review");
            }
            try(var q=connection.createStatement()) {
                q.executeUpdate("ALTER TABLE `"+table+"` MODIFY COLUMN `"+column+"` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin "+(nullable?"NULL":"NOT NULL"));
            }
        }
    }
}
