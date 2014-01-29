package net.jodah.lyra.internal.util.concurrent;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import net.jodah.lyra.util.Duration;

/**
 * A circuit that accepts re-entrant {@link #open()}, allows waiting threads to be interrupted, and
 * {@link #close()} calls and ensures fairness when releasing {@link #await() waiting} threads.
 * 
 * @author Daniel McGreal
 */
public class ReentrantCircuit {
	private ReentrantLock lock = new ReentrantLock(true);
	private Condition open; //null if closed
	private Set<Thread> waitingThreads = new HashSet<Thread>();

	/**
	 * Waits for the circuit to be closed, aborting if interrupted.
	 */
	public void await() throws InterruptedException {
		await(Duration.INFINITE);
	}

	/**
	 * Waits for the {@code waitDuration} until the circuit has been closed, aborting if interrupted,
	 * returning true if the circuit is closed else false.
	 */
	public boolean await(Duration waitDuration) throws InterruptedException {
		lock.lock();
		try{
			if(open == null) return true;
			waitingThreads.add(Thread.currentThread());
			return open.await(waitDuration.length, waitDuration.timeUnit);
		}finally{
			waitingThreads.remove(Thread.currentThread());
			lock.unlock();
		}
	}

	/**
	 * Closes the circuit, releasing any waiting threads.
	 */
	public void close() {
		lock.lock();
		try{
			if(open == null) return; //Already closed.
			open.signalAll();
			open = null;
		}finally{
			lock.unlock();
		}
	}

	/**
	 * Interrupts waiting threads.
	 */
	public void interruptWaiters() {
		for (Thread t : waitingThreads)
			t.interrupt();
	}

	/**
	 * Returns whether the circuit is closed.
	 */
	public boolean isClosed() {
		lock.lock();
		try{
			return open == null;
		}finally{
			lock.unlock();
		}
	}

	/**
	 * Opens the circuit.
	 */
	public void open() {
		lock.lock();
		try{
			if(open != null) return; //Already open.
			open = lock.newCondition();
		}finally{
			lock.unlock();
		}
	}

	@Override
	public String toString() {
		return isClosed() ? "closed" : "open";
	}
}
