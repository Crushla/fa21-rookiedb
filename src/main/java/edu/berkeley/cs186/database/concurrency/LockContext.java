package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LockContext wraps around LockManager to provide the hierarchical structure
 * of multigranularity locking. Calls to acquire/release/etc. locks should
 * be mostly done through a LockContext, which provides access to locking
 * methods at a certain point in the hierarchy (database, table X, etc.)
 */
public class LockContext {
    // You should not remove any of these fields. You may add additional
    // fields/methods as you see fit.

    // The underlying lock manager.
    protected final LockManager lockman;

    // The parent LockContext object, or null if this LockContext is at the top of the hierarchy.
    protected final LockContext parent;

    // The name of the resource this LockContext represents.
    protected ResourceName name;

    // Whether this LockContext is readonly. If a LockContext is readonly, acquire/release/promote/escalate should
    // throw an UnsupportedOperationException.
    //这个LockContext是否为只读
    protected boolean readonly;

    // A mapping between transaction numbers, and the number of locks on children of this LockContext
    // that the transaction holds.
    //事务号和锁数量之间的映射
    protected final Map<Long, Integer> numChildLocks;

    // You should not modify or use this directly.
    protected final Map<String, LockContext> children;

    // Whether or not any new child LockContexts should be marked readonly.
    protected boolean childLocksDisabled;

    public LockContext(LockManager lockman, LockContext parent, String name) {
        this(lockman, parent, name, false);
    }

    protected LockContext(LockManager lockman, LockContext parent, String name,
                          boolean readonly) {
        this.lockman = lockman;
        this.parent = parent;
        if (parent == null) {
            this.name = new ResourceName(name);
        } else {
            this.name = new ResourceName(parent.getResourceName(), name);
        }
        this.readonly = readonly;
        this.numChildLocks = new ConcurrentHashMap<>();
        this.children = new ConcurrentHashMap<>();
        this.childLocksDisabled = readonly;
    }

    /**
     * Gets a lock context corresponding to `name` from a lock manager.
     */
    public static LockContext fromResourceName(LockManager lockman, ResourceName name) {
        Iterator<String> names = name.getNames().iterator();
        LockContext ctx;
        String n1 = names.next();
        ctx = lockman.context(n1);
        while (names.hasNext()) {
            String n = names.next();
            ctx = ctx.childContext(n);
        }
        return ctx;
    }

    /**
     * Get the name of the resource that this lock context pertains to.
     */
    public ResourceName getResourceName() {
        return name;
    }

    /**
     * Acquire a `lockType` lock, for transaction `transaction`.
     *
     * Note: you must make any necessary updates to numChildLocks, or else calls
     * to LockContext#getNumChildren will not work properly.
     *
     * @throws InvalidLockException if the request is invalid
     * @throws DuplicateLockRequestException if a lock is already held by the
     * transaction.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void acquire(TransactionContext transaction, LockType lockType)
            throws InvalidLockException, DuplicateLockRequestException {
        // TODO(proj4_part2): implement
        if (readonly){
            throw new UnsupportedOperationException("read only");
        }
        if (lockType == getExplicitLockType(transaction)){
            throw new DuplicateLockRequestException("already held");
        }
        if (parentContext()!=null){
            LockType type = parentContext().getExplicitLockType(transaction);
            if (!LockType.canBeParentLock(type,lockType)){
                throw new InvalidLockException("Invalid");
            }
        }
        lockman.acquire(transaction,name,lockType);
        if (parentContext()!=null) {
            parentContext().numChildLocks.put(transaction.getTransNum(), parentContext().getNumChildren(transaction) + 1);
        }
    }

    /**
     * Release `transaction`'s lock on `name`.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or
     * else calls to LockContext#getNumChildren will not work properly.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     * @throws InvalidLockException if the lock cannot be released because
     * doing so would violate multigranularity locking constraints
     * @throws UnsupportedOperationException if context is readonly
     */
    public void release(TransactionContext transaction)
            throws NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        if (readonly){
            throw new UnsupportedOperationException("readonly");
        }
        if (getExplicitLockType(transaction)==LockType.NL){
            throw new NoLockHeldException("no lock");
        }
        //Attemptng to release an IS lock when a child resource still holds an S locks should throw an InvalidLockException
        for (LockContext child:children.values()){
            if (!LockType.canBeParentLock(LockType.NL,child.getExplicitLockType(transaction))){
                throw new InvalidLockException("invalid");
            }
        }
        lockman.release(transaction,name);
        if (parentContext()!=null) {
            parentContext().numChildLocks.put(transaction.getTransNum(), parentContext().getNumChildren(transaction) - 1);
        }
    }

    /**
     * Promote `transaction`'s lock to `newLockType`. For promotion to SIX from
     * IS/IX, all S and IS locks on descendants must be simultaneously
     * released. The helper function sisDescendants may be helpful here.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or else
     * calls to LockContext#getNumChildren will not work properly.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock
     * @throws NoLockHeldException if `transaction` has no lock
     * @throws InvalidLockException if the requested lock type is not a
     * promotion or promoting would cause the lock manager to enter an invalid
     * state (e.g. IS(parent), X(child)). A promotion from lock type A to lock
     * type B is valid if B is substitutable for A and B is not equal to A, or
     * if B is SIX and A is IS/IX/S, and invalid otherwise. hasSIXAncestor may
     * be helpful here.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void promote(TransactionContext transaction, LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        LockType type = getExplicitLockType(transaction);
        if (readonly){
            throw new UnsupportedOperationException("readonly");
        }
        if (type == LockType.NL){
            throw new NoLockHeldException("no lock");
        }
        if (type == newLockType){
            throw new DuplicateLockRequestException("already has a newLockType lock");
        }
        if (parentContext()!=null){
            if (!LockType.substitutable(newLockType,type)){
                throw new InvalidLockException("InvalidLock");
            }
        }
        if (newLockType == LockType.SIX && type.isIntent()){
            List<ResourceName> resourceNames = sisDescendants(transaction);
            resourceNames.add(name);
            lockman.acquireAndRelease(transaction,name,newLockType,resourceNames);
            numChildLocks.put(transaction.getTransNum(), getNumChildren(transaction) - resourceNames.size());
        } else{
            lockman.promote(transaction, name, newLockType);
        }
    }

    /**
     * Escalate `transaction`'s lock from descendants of this context to this
     * level, using either an S or X lock. There should be no descendant locks
     * after this call, and every operation valid on descendants of this context
     * before this call must still be valid. You should only make *one* mutating
     * call to the lock manager, and should only request information about
     * TRANSACTION from the lock manager.
     *
     * For example, if a transaction has the following locks:
     *
     *                    IX(database)
     *                    /         \
     *               IX(table1)    S(table2)
     *                /      \
     *    S(table1 page3)  X(table1 page5)
     *
     * then after table1Context.escalate(transaction) is called, we should have:
     *
     *                    IX(database)
     *                    /         \
     *               X(table1)     S(table2)
     *
     * You should not make any mutating calls if the locks held by the
     * transaction do not change (such as when you call escalate multiple times
     * in a row).
     *
     * Note: you *must* make any necessary updates to numChildLocks of all
     * relevant contexts, or else calls to LockContext#getNumChildren will not
     * work properly.
     *
     * @throws NoLockHeldException if `transaction` has no lock at this level
     * @throws UnsupportedOperationException if context is readonly
     */
    public void escalate(TransactionContext transaction) throws NoLockHeldException {
        // TODO(proj4_part2): implement
        LockType type = getExplicitLockType(transaction);
        if (readonly){
            throw new UnsupportedOperationException("readonly");
        }
        if (type == LockType.NL){
            throw new NoLockHeldException("no lock");
        }
        LockType newType = type;
        List<ResourceName> resourceNames = new ArrayList<>();
        resourceNames.add(name);
        for (LockContext context: children.values()) {
            LockType explicitLockType = context.getExplicitLockType(transaction);
            if (!context.readonly && explicitLockType != LockType.NL) {
                resourceNames.add(context.name);
            }
            if (LockType.substitutable(explicitLockType,newType)){
                newType = explicitLockType;
            }
        }
        if (newType == LockType.IS){
            newType = LockType.S;
        }else if (newType.isIntent()){
            newType = LockType.X;
        }
        if (newType!=type){
            lockman.acquireAndRelease(transaction,name,newType,resourceNames);
        }
        numChildLocks.put(transaction.getTransNum(),0);
    }

    /**
     * Get the type of lock that `transaction` holds at this level, or NL if no
     * lock is held at this level.
     */
    //获取事务在此级别持有的锁的类型，如果在此级别没有持有锁，则获取NL。
    public LockType getExplicitLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        return lockman.getLockType(transaction,name);
    }

    /**
     * Gets the type of lock that the transaction has at this level, either
     * implicitly (e.g. explicit S lock at higher level implies S lock at this
     * level) or explicitly. Returns NL if there is no explicit nor implicit
     * lock.
     */
    //获取事务在此级别拥有的锁的类型，可以是隐式的(例如，更高级别的显式S锁意味着在此级别的S锁)，也可以是显式的。如果没有显式或隐式锁，则返回NL。
    public LockType getEffectiveLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        LockType explicitLockType = getExplicitLockType(transaction);
        if (parent==null){
            if (explicitLockType.isIntent()){
                return LockType.NL;
            }
            return explicitLockType;
        }
        if (explicitLockType !=LockType.NL){
            if (explicitLockType == LockType.SIX){
                explicitLockType = LockType.S;
            }
            return explicitLockType;
        }
        return parentContext().getEffectiveLockType(transaction);
    }

    /**
     * Helper method to see if the transaction holds a SIX lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a SIX at an ancestor, false if not
     */
    //查看事务是否在该上下文的祖先处持有SIX锁
    private boolean hasSIXAncestor(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        if (parent == null){
            return false;
        }
        if (parentContext().getExplicitLockType(transaction)==LockType.SIX){
            return true;
        }
        return parentContext().hasSIXAncestor(transaction);
    }

    /**
     * Helper method to get a list of resourceNames of all locks that are S or
     * IS and are descendants of current context for the given transaction.
     * @param transaction the given transaction
     * @return a list of ResourceNames of descendants which the transaction
     * holds an S or IS lock.
     */
    //获取所有锁的resourcename列表，这些锁是S或IS，并且是给定事务的当前上下文的后代
    private List<ResourceName> sisDescendants(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        List<ResourceName> list = new ArrayList<>();
        for (LockContext lock:children.values()){
            LockType type = lock.getExplicitLockType(transaction);
            if (type == LockType.IS||type == LockType.S){
                list.add(lock.getResourceName());
            }
        }
        return list;
    }

    /**
     * Disables locking descendants. This causes all new child contexts of this
     * context to be readonly. This is used for indices and temporary tables
     * (where we disallow finer-grain locks), the former due to complexity
     * locking B+ trees, and the latter due to the fact that temporary tables
     * are only accessible to one transaction, so finer-grain locks make no
     * sense.
     */
    //禁用锁的后代。这将导致该上下文的所有新子上下文都是只读的。
    // 这用于索引和临时表(我们不允许细粒度锁)，前者是由于锁B+树的复杂性，后者是由于临时表只能被一个事务访问，所以细粒度锁没有意义。
    public void disableChildLocks() {
        this.childLocksDisabled = true;
    }

    /**
     * Gets the parent context.
     */
    public LockContext parentContext() {
        return parent;
    }

    /**
     * Gets the context for the child with name `name` and readable name
     * `readable`
     */
    public synchronized LockContext childContext(String name) {
        LockContext temp = new LockContext(lockman, this, name,
                this.childLocksDisabled || this.readonly);
        LockContext child = this.children.putIfAbsent(name, temp);
        if (child == null) child = temp;
        return child;
    }

    /**
     * Gets the context for the child with name `name`.
     */
    public synchronized LockContext childContext(long name) {
        return childContext(Long.toString(name));
    }

    /**
     * Gets the number of locks held on children a single transaction.
     */
    public int getNumChildren(TransactionContext transaction) {
        return numChildLocks.getOrDefault(transaction.getTransNum(), 0);
    }

    @Override
    public String toString() {
        return "LockContext(" + name.toString() + ")";
    }
}

