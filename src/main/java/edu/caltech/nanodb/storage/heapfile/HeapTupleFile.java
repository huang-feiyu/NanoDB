package edu.caltech.nanodb.storage.heapfile;


import edu.caltech.nanodb.queryeval.ColumnStats;
import edu.caltech.nanodb.queryeval.ColumnStatsCollector;
import edu.caltech.nanodb.queryeval.TableStats;
import edu.caltech.nanodb.relations.Schema;
import edu.caltech.nanodb.relations.Tuple;
import edu.caltech.nanodb.storage.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * This class implements the TupleFile interface for heap files.
 */
public class HeapTupleFile implements TupleFile {

    /**
     * A logging object for reporting anything interesting that happens.
     */
    private static Logger logger = LogManager.getLogger(HeapTupleFile.class);


    /**
     * The storage manager to use for reading and writing file pages, pinning
     * and unpinning pages, write-ahead logging, and so forth.
     */
    private StorageManager storageManager;


    /**
     * The manager for heap tuple files provides some higher-level operations
     * such as saving the metadata of a heap tuple file, so it's useful to
     * have a reference to it.
     */
    private HeapTupleFileManager heapFileManager;


    /**
     * The schema of tuples in this tuple file.
     */
    private Schema schema;


    /**
     * Statistics for this tuple file.
     */
    private TableStats stats;


    /**
     * The file that stores the tuples.
     */
    private DBFile dbFile;


    HeapTupleFile(StorageManager storageManager,
                  HeapTupleFileManager heapFileManager, DBFile dbFile,
                  Schema schema, TableStats stats) {
        if (storageManager == null)
            throw new IllegalArgumentException("storageManager cannot be null");

        if (heapFileManager == null)
            throw new IllegalArgumentException("heapFileManager cannot be null");

        if (dbFile == null)
            throw new IllegalArgumentException("dbFile cannot be null");

        if (schema == null)
            throw new IllegalArgumentException("schema cannot be null");

        if (stats == null)
            throw new IllegalArgumentException("stats cannot be null");

        this.storageManager = storageManager;
        this.heapFileManager = heapFileManager;
        this.dbFile = dbFile;
        this.schema = schema;
        this.stats = stats;
    }


    @Override
    public TupleFileManager getManager() {
        return heapFileManager;
    }


    @Override
    public Schema getSchema() {
        return schema;
    }

    @Override
    public TableStats getStats() {
        return stats;
    }


    public DBFile getDBFile() {
        return dbFile;
    }


    /**
     * Returns the first tuple in this table file, or <tt>null</tt> if
     * there are no tuples in the file.
     */
    @Override
    public Tuple getFirstTuple() {
        HeapFilePageTuple first = null;

        // Scan through the data pages until we hit the end of the table file.
        // It may be that the first run of data pages is empty, so just keep
        // looking until we hit the end of the file.

        // Header page is page 0, so first data page is page 1.
        page_scan:
        // So we can break out of the outer loop from inside the inner one
        for (int iPage = 1; /* nothing */ ; iPage++) {
            // Try to load the page.  If it doesn't exist, exit the loop.
            // implicitly: dbPage.pin()
            DBPage dbPage = storageManager.loadDBPage(dbFile, iPage);
            if (dbPage == null)
                break;

            // Look for data on this page.
            int numSlots = DataPage.getNumSlots(dbPage);
            for (int iSlot = 0; iSlot < numSlots; iSlot++) {
                // Get the offset of the tuple in the page.  If it's 0 then
                // the slot is empty, and we skip to the next slot.
                int offset = DataPage.getSlotValue(dbPage, iSlot);
                if (offset == DataPage.EMPTY_SLOT)
                    continue;

                // This is the first tuple in the file.  Build up the
                // HeapFilePageTuple object and return it.  Note that
                // creating this page-tuple will increment the page's
                // pin-count by one, so we decrement the page pin-count
                // before breaking out.
                first = new HeapFilePageTuple(schema, dbPage, iSlot, offset);
                dbPage.unpin();

                break page_scan;
            }

            // Didn't find any tuples in this page, so unpin before we
            // move on to the next page.
            dbPage.unpin();
        }

        return first;
    }


    /**
     * Returns the tuple corresponding to the specified file pointer.  This
     * method is used by many other operations in the database, such as
     * indexes.
     *
     * @throws InvalidFilePointerException if the specified file-pointer
     *                                     doesn't actually point to a real tuple.
     */
    @Override
    public Tuple getTuple(FilePointer fptr) throws InvalidFilePointerException {

        // implicitly: dbPage.pin()
        DBPage dbPage = storageManager.loadDBPage(dbFile, fptr.getPageNo());
        if (dbPage == null) {
            throw new InvalidFilePointerException(String.format(
                "Specified page %d doesn't exist in file %s",
                fptr.getPageNo(), dbFile.getDataFile().getName()));
        }

        // The file-pointer points to the slot for the tuple, not the tuple itself.
        // So, we need to look up that slot's value to get to the tuple data.

        int slot;
        try {
            slot = DataPage.getSlotIndexFromOffset(dbPage, fptr.getOffset());
        } catch (IllegalArgumentException iae) {
            throw new InvalidFilePointerException(iae);
        }

        // Pull the tuple's offset from the specified slot, and make sure
        // there is actually a tuple there!

        int offset = DataPage.getSlotValue(dbPage, slot);
        if (offset == DataPage.EMPTY_SLOT) {
            throw new InvalidFilePointerException("Slot " + slot +
                " on page " + fptr.getPageNo() + " is empty.");
        }

        // Unpin dbPage since getTuple implicitly pin both tuple and dbPage
        dbPage.unpin();

        return new HeapFilePageTuple(schema, dbPage, slot, offset);
    }


    /**
     * Returns the tuple that follows the specified tuple, or {@code null} if
     * there are no more tuples in the file.  This method must operate
     * correctly regardless of whether the input tuple is pinned or unpinned.
     *
     * @param tup the "previous tuple" that specifies where to start looking
     *            for the next tuple
     */
    @Override
    public Tuple getNextTuple(Tuple tup) {

        /* Procedure:
         *   1)  Get slot index of current tuple.
         *   2)  If there are more slots in the current page, find the next
         *       non-empty slot.
         *   3)  If we get to the end of this page, go to the next page
         *       and try again.
         *   4)  If we get to the end of the file, we return null.
         */

        if (!(tup instanceof HeapFilePageTuple)) {
            throw new IllegalArgumentException(
                "Tuple must be of type HeapFilePageTuple; got " + tup.getClass());
        }
        HeapFilePageTuple ptup = (HeapFilePageTuple) tup;

        // Retrieve the location info from the previous tuple.  Since the
        // tuple (and/or its backing page) may already have a pin-count of 0,
        // we can't necessarily use the page itself.
        DBPage prevDBPage = ptup.getDBPage();
        DBFile dbFile = prevDBPage.getDBFile();
        int prevPageNo = prevDBPage.getPageNo();
        int prevSlot = ptup.getSlot();

        // Retrieve the page itself so that we can access the internal data.
        // The page will come back pinned on behalf of the caller.  (If the
        // page is still in the Buffer Manager's cache, it will not be read
        // from disk, so this won't be expensive in that case.)
        // implicitly dbPage.pin()
        DBPage dbPage = storageManager.loadDBPage(dbFile, prevPageNo);
        if (dbPage == null)
            throw new DataFormatException("Couldn't load the tuple's page");

        HeapFilePageTuple nextTup = null;

        // Start by looking at the slot immediately following the previous
        // tuple's slot.
        int nextSlot = prevSlot + 1;

        page_scan:
        // So we can break out of the outer loop from inside the inner loop.
        while (true) {
            int numSlots = DataPage.getNumSlots(dbPage);

            while (nextSlot < numSlots) {
                int nextOffset = DataPage.getSlotValue(dbPage, nextSlot);
                if (nextOffset != DataPage.EMPTY_SLOT) {
                    // Creating this tuple will pin the page a second time.
                    // Thus, we unpin the page after creating this tuple.
                    nextTup = new HeapFilePageTuple(schema, dbPage, nextSlot,
                        nextOffset);
                    // Find the tuple, everything done
                    dbPage.unpin();
                    break page_scan;
                }

                nextSlot++;
            }

            // If we got here then we reached the end of this page with no
            // tuples.  Go on to the next data-page, and start with the first
            // tuple in that page.

            // Didn't find any tuples in this page, so unpin before we
            // move on to the next page.
            dbPage.unpin();

            dbPage = storageManager.loadDBPage(dbFile, dbPage.getPageNo() + 1);
            if (dbPage == null)
                break;  // Hit EOF with no more tuples.  Done scanning.

            nextSlot = 0;
        }

        return nextTup;
    }


    /**
     * Adds the specified tuple into the table file.  A new
     * <tt>HeapFilePageTuple</tt> object corresponding to the tuple is returned.
     *
     * @review (donnie) This could be made a little more space-efficient.
     * Right now when computing the required space, we assume that we
     * will <em>always</em> need a new slot entry, whereas the page may
     * contain empty slots.  (Note that we don't always create a new
     * slot when adding a tuple; we will reuse an empty slot.  This
     * inefficiency is simply in estimating the size required for the
     * new tuple.)
     */
    @Override
    public Tuple addTuple(Tuple tup) {

        /*
         * Find out how large the new tuple will be, so we can find a page to
         * store it.
         *
         * Find a page with space for the new tuple.
         *
         * Generate the data necessary for storing the tuple into the file.
         */

        int tupSize = PageTuple.getTupleStorageSize(schema, tup);
        logger.debug("Adding new tuple of size " + tupSize + " bytes.");

        // Sanity check:  Make sure that the tuple would actually fit in a page
        // in the first place!
        // The "+ 2" is for the case where we need a new slot entry as well.
        if (tupSize + 2 > dbFile.getPageSize()) {
            throw new TupleFileException("Tuple size " + tupSize +
                " is larger than page size " + dbFile.getPageSize() + ".");
        }

        // Search for a page to put the tuple in.  If we hit the end of the
        // data file, create a new page.
        DBPage dbPage = null;
        // implicitly: headerPage.pin()
        DBPage headerPage = storageManager.loadDBPage(dbFile, 0);

        int pageNo = HeaderPage.getFreeHead(headerPage);
        // pageNo=0  => No free page
        while (pageNo != 0) {
            // Try to load the page without creating a new one.
            // implicitly: dbPage.pin()
            dbPage = storageManager.loadDBPage(dbFile, pageNo);

            int freeSpace = DataPage.getFreeSpaceInPage(dbPage);
            logger.trace(String.format("Page %d has %d bytes of free space.", pageNo, freeSpace));

            // If this page has enough free space to add a new tuple, break
            // out of the loop.  (The "+ 2" is for the new slot entry we will
            // also need.)
            if (freeSpace >= tupSize + 2) {
                logger.debug("Found space for new tuple in page " + pageNo + ".");
                break;
            }

            // NOTE: Do not consider VARCHAR, some waste of page
            // If we reached this point then the page doesn't have enough
            // space, so go on to the next data page.
            pageNo = DataPage.getFreeNext(dbPage);

            // Mark as not free, evict from free page list
            DataPage.setFreeNext(dbPage, DataPage.INVALID_PGNO);
            HeaderPage.setFreeHead(headerPage, pageNo);

            dbPage.unpin();
        }

        // no more free page, create new page
        if (pageNo == 0) {
            pageNo = dbFile.getNumPages(); // #head + #data
            logger.debug("Creating new page " + pageNo + " to store new tuple.");
            // implicitly: dbPage.pin()
            dbPage = storageManager.loadDBPage(dbFile, pageNo, true);
            DataPage.initNewPage(dbPage);

            // Add to free list
            HeaderPage.setFreeHead(headerPage, pageNo);
            DataPage.setFreeNext(dbPage, 0);
        }

        int slot = DataPage.allocNewTuple(dbPage, tupSize);
        int tupOffset = DataPage.getSlotValue(dbPage, slot);

        logger.debug(String.format("New tuple will reside on page %d, slot %d.", pageNo, slot));

        HeapFilePageTuple pageTup = HeapFilePageTuple.storeNewTuple(schema, dbPage, slot, tupOffset, tup);

        DataPage.sanityCheck(dbPage);

        // Unpin dbPage since storeNewTuple implicitly pin both tuple and dbPage
        dbPage.unpin();
        headerPage.unpin();

        storageManager.logDBPageWrite(dbPage);
        storageManager.logDBPageWrite(headerPage);
        return pageTup;
    }


    // Inherit interface-method documentation.

    /**
     * @review (donnie) This method will fail if a tuple is modified in a way
     * that requires more space than is currently available in the data
     * page.  One solution would be to move the tuple to a different
     * page and then perform the update, but that would cause all kinds
     * of additional issues.  So, if the page runs out of data, oh well.
     */
    @Override
    public void updateTuple(Tuple tup, Map<String, Object> newValues) {

        if (!(tup instanceof HeapFilePageTuple)) {
            throw new IllegalArgumentException("Tuple must be of type HeapFilePageTuple; got " + tup.getClass());
        }
        HeapFilePageTuple ptup = (HeapFilePageTuple) tup;

        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            String colName = entry.getKey();
            Object value = entry.getValue();

            int colIndex = schema.getColumnIndex(colName);
            ptup.setColumnValue(colIndex, value);
        }

        DBPage dbPage = ptup.getDBPage();
        DataPage.sanityCheck(dbPage);

        storageManager.logDBPageWrite(dbPage);
    }


    // Inherit interface-method documentation.
    @Override
    public void deleteTuple(Tuple tup) {

        if (!(tup instanceof HeapFilePageTuple)) {
            throw new IllegalArgumentException("Tuple must be of type HeapFilePageTuple; got " + tup.getClass());
        }
        HeapFilePageTuple ptup = (HeapFilePageTuple) tup;

        DBPage dbPage = ptup.getDBPage();
        DataPage.deleteTuple(dbPage, ptup.getSlot());
        DataPage.sanityCheck(dbPage);

        if (DataPage.getFreeNext(dbPage) != DataPage.INVALID_PGNO) {
            // already in free list
            return;
        }

        // implicitly: headerPage.pin()
        var headerPage = storageManager.loadDBPage(dbFile, 0);

        // Insert the page into head->next
        var prev = HeaderPage.getFreeHead(headerPage);
        HeaderPage.setFreeHead(headerPage, dbPage.getPageNo());
        DataPage.setFreeNext(dbPage, prev);

        headerPage.unpin();

        storageManager.logDBPageWrite(headerPage);
        storageManager.logDBPageWrite(dbPage); // has been deleted
    }


    /**
     * <tt>ANALYZE</tt> to collect table statistics and each column's statistics.
     *
     * <p>
     * For each table, store table statistics in `TableStats` <br>
     * 1. T(R): number of tuples in R <br>
     * 2. A(R): average size of a tuple in R <br>
     * 3. B(R): number of data pages in R <br>
     *
     * <p>
     * For each column of a table, store column statistics in `ColumnStats` <br>
     * 1. V(R,c): number of distinct non-null values of column c in R <br>
     * 2. N(R,c): number of null values of column c in R <br>
     * 3. MAX(R,c): maximum value of column c in R (do not collect for string) <br>
     * 4. MIN(R,c): minimum value of column c in R <br>
     */
    @Override
    public void analyze() {
        var numCols = schema.numColumns();

        var numTuples = 0;
        var numPages = 0;
        var totalSize = 0;
        var collectors = new ArrayList<ColumnStatsCollector>();
        for (int i = 0; i < numCols; i++) {
            collectors.add(new ColumnStatsCollector(schema.getColumnInfo(i).getType().getBaseType()));
        }

        // For each page
        for (int iPage = 1; /* dbPage is not null */ ; iPage++) {
            var page = storageManager.loadDBPage(dbFile, iPage);
            if (page == null)
                break; // no more page

            numPages++;
            totalSize += DataPage.getTupleDataEnd(page) - DataPage.getTupleDataStart(page);
            // For each tuple in the page
            for (int i = 0; i < DataPage.getNumSlots(page); i++) {
                var offset = DataPage.getSlotValue(page, i);
                if (offset == DataPage.EMPTY_SLOT) {
                    continue;
                }
                numTuples += 1;
                var tup = new HeapFilePageTuple(schema, page, i, offset);
                // For each column in the tuple
                for (int k = 0; k < numCols; k++) {
                    collectors.get(k).addValue(tup.getColumnValue(k));
                }
                tup.unpin();
            }
            page.unpin();
        }

        // Maintain stats in data structures
        var cols = new ArrayList<ColumnStats>();
        for (int i = 0; i < numCols; i++) {
            cols.add(collectors.get(i).getColumnStats());
        }
        stats = new TableStats(numPages, numTuples, (float) totalSize / numTuples, cols);
        // Serialize them
        heapFileManager.saveMetadata(this);
    }


    @Override
    public List<String> verify() {
        // TODO!
        // Right now we will just report that everything is fine.
        return new ArrayList<>();
    }


    @Override
    public void optimize() {
        // TODO!
        throw new UnsupportedOperationException("Not yet implemented!");
    }
}
