package edu.caltech.nanodb.commands;


import edu.caltech.nanodb.indexes.IndexManager;
import edu.caltech.nanodb.relations.TableInfo;
import edu.caltech.nanodb.server.NanoDBServer;
import edu.caltech.nanodb.storage.StorageManager;
import edu.caltech.nanodb.storage.TableManager;


/**
 * This command-class represents the <tt>DROP INDEX</tt> DDL command.
 */
public class DropIndexCommand extends Command {
    /**
     * The name of the index to drop.
     */
    private String indexName;


    /**
     * The name of the table that the index is built against.
     */
    private String tableName;


    /**
     * This flag controls whether the drop-index command will fail if the
     * index already doesn't exist when the removal is attempted.
     */
    private boolean ifExists;


    public DropIndexCommand(String indexName, String tableName,
                            boolean ifExists) {
        super(Type.DDL);

        if (tableName == null)
            throw new IllegalArgumentException("tableName cannot be null");

        this.indexName = indexName;
        this.tableName = tableName;
        this.ifExists = ifExists;
    }


    /**
     * Get the name of the table containing the index to be dropped.
     *
     * @return the name of the table containing the index to drop
     */
    public String getTableName() {
        return tableName;
    }


    /**
     * Get the name of the index to be dropped.
     *
     * @return the name of the index to drop
     */
    public String getIndexName() {
        return indexName;
    }


    /**
     * Returns the value of the "if exists" flag; true indicates that it is
     * not an error if the index doesn't exist when this command is issued.
     *
     * @return the value of the "if exists" flag
     */
    public boolean getIfExists() {
        return ifExists;
    }


    @Override
    public void execute(NanoDBServer server) throws ExecutionException {

        StorageManager storageManager = server.getStorageManager();
        TableManager tableManager = storageManager.getTableManager();
        IndexManager indexManager = storageManager.getIndexManager();

        // Open the table, then attempt to drop the index.  If it works,
        // save the table's schema back to the table file.
        TableInfo tableInfo = tableManager.openTable(tableName);
        indexManager.dropIndex(tableInfo, indexName);
        tableManager.saveTableInfo(tableInfo);

        out.printf("Dropped index %s on table %s.%n", indexName, tableName);
    }
}
