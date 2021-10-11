package edu.berkeley.cs186.database.query.join;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.iterator.BacktrackingIterator;
import edu.berkeley.cs186.database.query.JoinOperator;
import edu.berkeley.cs186.database.query.QueryOperator;
import edu.berkeley.cs186.database.table.Record;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Performs an equijoin between two relations on leftColumnName and
 * rightColumnName respectively using the Block Nested Loop Join algorithm.
 */
public class BNLJOperator extends JoinOperator {
    protected int numBuffers;

    public BNLJOperator(QueryOperator leftSource,
                        QueryOperator rightSource,
                        String leftColumnName,
                        String rightColumnName,
                        TransactionContext transaction) {
        super(leftSource, materialize(rightSource, transaction),
                leftColumnName, rightColumnName, transaction, JoinType.BNLJ
        );
        this.numBuffers = transaction.getWorkMemSize();
        this.stats = this.estimateStats();
    }

    @Override
    public Iterator<Record> iterator() {
        return new BNLJIterator();
    }

    @Override
    public int estimateIOCost() {
        //This method implements the IO cost estimation of the Block Nested Loop Join
        int usableBuffers = numBuffers - 2;
        int numLeftPages = getLeftSource().estimateStats().getNumPages();
        int numRightPages = getRightSource().estimateIOCost();
        return ((int) Math.ceil((double) numLeftPages / (double) usableBuffers)) * numRightPages +
               getLeftSource().estimateIOCost();
    }

    /**
     * A record iterator that executes the logic for a simple nested loop join.
     * Look over the implementation in SNLJOperator if you want to get a feel
     * for the fetchNextRecord() logic.
     */
    private class BNLJIterator implements Iterator<Record>{
        // Iterator over all the records of the left source
        private Iterator<Record> leftSourceIterator;
        // Iterator over all the records of the right source
        private BacktrackingIterator<Record> rightSourceIterator;
        // Iterator over records in the current block of left pages
        private BacktrackingIterator<Record> leftBlockIterator;
        // Iterator over records in the current right page
        private BacktrackingIterator<Record> rightPageIterator;
        // The current record from the left relation
        private Record leftRecord;
        // The next record to return
        private Record nextRecord;

        private BNLJIterator() {
            super();
            this.leftSourceIterator = getLeftSource().iterator();
            this.fetchNextLeftBlock();

            this.rightSourceIterator = getRightSource().backtrackingIterator();
            this.rightSourceIterator.markNext();
            this.fetchNextRightPage();

            this.nextRecord = null;
        }

        /**
         * Fetch the next block of records from the left source.
         * leftBlockIterator should be set to a backtracking iterator over up to
         * B-2 pages of records from the left source, and leftRecord should be
         * set to the first record in this block.
         *
         * If there are no more records in the left source, this method should
         * do nothing.
         *
         * You may find QueryOperator#getBlockIterator useful here.
         */
        private void fetchNextLeftBlock() {
            // TODO(proj3_part1): implement
            //根据注释
            //首先leftBlockIterator应该设置为一个回朔迭代器,覆盖左源到B-2页的记录
            //leftRecord应该设置为该块的第一个记录
            //如果左源没有更多记录，则该方法什么也不做
            if (leftSourceIterator.hasNext()) {
                leftBlockIterator = getBlockIterator(leftSourceIterator,getLeftSource().getSchema(),numBuffers-2);
                leftBlockIterator.markNext();
                leftRecord = leftBlockIterator.next();
            }
        }

        /**
         * Fetch the next page of records from the right source.
         * rightPageIterator should be set to a backtracking iterator over up to
         * one page of records from the right source.
         *
         * If there are no more records in the right source, this method should
         * do nothing.
         *
         * You may find QueryOperator#getBlockIterator useful here.
         */
        private void fetchNextRightPage() {
            // TODO(proj3_part1): implement
            if (rightSourceIterator.hasNext()){
                rightPageIterator = getBlockIterator(rightSourceIterator,getRightSource().getSchema(),1);
                rightPageIterator.markNext();
            }
        }

        /**
         * Returns the next record that should be yielded from this join,
         * or null if there are no more records to join.
         *
         * You may find JoinOperator#compare useful here. (You can call compare
         * function directly from this file, since BNLJOperator is a subclass
         * of JoinOperator).
         */
        private Record fetchNextRecord() {
            // TODO(proj3_part1): implement
            if (leftRecord == null){
                return null;
            }
            //左边的每块和右边的每页中的数据进行匹配
            while(true){
                //页中数据没有用完，就获取下一个记录进行匹配
                if(rightPageIterator.hasNext()){
                    Record rightRecord = rightPageIterator.next();
                    if(compare(leftRecord,rightRecord)==0){
                        return leftRecord.concat(rightRecord);
                    }
                }
                //块中数据已经用完，但是块中数据还有，就将块迭代到下一个，页重置
                else if (leftBlockIterator.hasNext()){
                    leftRecord = leftBlockIterator.next();
                    rightPageIterator.reset();
                }
                //块中数据和页中数据已经用完，但是还有页
                else if (rightSourceIterator.hasNext()){
                    //迭代到下一页中
                    fetchNextRightPage();
                    //将当前块迭代器重置，并将其指向第一个记录
                    leftBlockIterator.reset();
                    leftRecord = leftBlockIterator.next();
                }
                //块中记录和页中记录都已经用完，但是页已经用完
                else if(leftSourceIterator.hasNext()){
                    //块迭代器指向下一个块
                    fetchNextLeftBlock();
                    //页的源迭代器重置
                    rightSourceIterator.reset();
                    //并将页迭代器指向第一页
                    fetchNextRightPage();
                }else {
                    return null;
                }
            }
        }

        /**
         * @return true if this iterator has another record to yield, otherwise
         * false
         */
        @Override
        public boolean hasNext() {
            if (this.nextRecord == null) this.nextRecord = fetchNextRecord();
            return this.nextRecord != null;
        }

        /**
         * @return the next record from this iterator
         * @throws NoSuchElementException if there are no more records to yield
         */
        @Override
        public Record next() {
            if (!this.hasNext()) throw new NoSuchElementException();
            Record nextRecord = this.nextRecord;
            this.nextRecord = null;
            return nextRecord;
        }
    }
}
