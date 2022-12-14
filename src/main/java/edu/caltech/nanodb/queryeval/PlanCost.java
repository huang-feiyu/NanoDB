package edu.caltech.nanodb.queryeval;


/**
 * This class holds a collection of values that represent the cost of an
 * operation against the database.  All of these cost estimates are ultimately
 * based on statistics computed from actual tables, but the estimation process
 * is very approximate.  For more involved plans, the best we can really hope
 * for is that better, faster plans will be assigned lower costs than worse,
 * slower plans.
 * <p>
 * Some of these values are floating-point values since they are affected by
 * selectivity estimates, which are always between 0 and 1.  Other values are
 * cumulative (e.g. the number of block reads and large seeks), so they are
 * represented as integer values.
 *
 * @see TableStats
 */
public class PlanCost {
    /**
     * The estimated number of tuples produced by the node.  We use a
     * floating-point value because the computations frequently involve
     * fractional numbers and it's not very effective to use integers or longs.
     */
    public float numTuples;


    /**
     * The average tuple size of tuples produced by the node.
     */
    public float tupleSize;


    /**
     * An estimate of the overall computational cost of an operation, in some
     * imaginary unit.  Each plan node must perform some amount of computation
     * to produce its results, and this cost is clearly accumulated as results
     * flow up the plan tree.
     */
    public float cpuCost;


    /**
     * The estimated number of disk-block accesses required to perform the
     * operation.
     */
    public long numBlockIOs;


    /**
     * The estimated number of large disk-seeks required to perform the
     * operation.
     */
    public long numLargeSeeks;


    /**
     * Constructs a PlanCost object from its component fields.
     *
     * @param numTuples     the estimated number of tuples that will be produced
     * @param tupleSize     the estimated size of the produced tuples in bytes
     * @param cpuCost       an estimate of the overall computational cost of the plan
     *                      node, in some imaginary unit
     * @param numBlockIOs   the estimated number of block reads and writes that
     *                      will be performed in evaluating the query
     * @param numLargeSeeks the estimated number of large disk-seeks that will
     *                      be performed in evaluating the query
     */
    public PlanCost(float numTuples, float tupleSize, float cpuCost,
                    long numBlockIOs, long numLargeSeeks) {

        this.numTuples = numTuples;
        this.tupleSize = tupleSize;
        this.cpuCost = cpuCost;
        this.numBlockIOs = numBlockIOs;
        this.numLargeSeeks = numLargeSeeks;
    }


    /**
     * Constructs a PlanCost object from another cost object.
     *
     * @param c the cost-object to duplicate
     */
    public PlanCost(PlanCost c) {
        this(c.numTuples, c.tupleSize, c.cpuCost,
            c.numBlockIOs, c.numLargeSeeks);
    }


    @Override
    public String toString() {
        return String.format("[tuples=%.1f, tupSize=%.1f, cpuCost=%.1f, " +
                "blockIOs=%d, largeSeeks=%d]", numTuples, tupleSize, cpuCost,
            numBlockIOs, numLargeSeeks);
    }
}
