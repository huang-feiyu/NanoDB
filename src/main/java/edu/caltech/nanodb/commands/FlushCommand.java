package edu.caltech.nanodb.commands;


import edu.caltech.nanodb.server.NanoDBServer;


/**
 * This command flushes all unwritten data from the buffer manager to disk.
 * A sync is not performed.
 */
public class FlushCommand extends Command {
    /**
     * Construct a new <tt>FLUSH</tt> command.
     */
    public FlushCommand() {
        super(Command.Type.UTILITY);
    }


    @Override
    public void execute(NanoDBServer server) throws ExecutionException {
        out.println("Flushing all unwritten data to disk.");
        server.getStorageManager().flushAllData();
    }


    /**
     * Prints a simple representation of the flush command.
     *
     * @return a string representing this flush command
     */
    @Override
    public String toString() {
        return "Flush";
    }
}
