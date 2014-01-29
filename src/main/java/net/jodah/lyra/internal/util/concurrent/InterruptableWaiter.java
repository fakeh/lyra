package net.jodah.lyra.internal.util.concurrent;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import net.jodah.lyra.util.Duration;

/**
 * A waiter where waiting threads can be interrupted (as opposed to awakened).
 * 
 * @author Jonathan Halterman
 */
public class InterruptableWaiter {
	private Set<Thread> waitingThreads = new HashSet<Thread>();


  /**
   * Waits forever, aborting if interrupted.
   */
  public void await() throws InterruptedException {
    await(Duration.INFINITE);
  }

  /**
   * Waits for the {@code waitDuration}, aborting if interrupted.
   */
  public void await(Duration waitDuration) throws InterruptedException {
	  try{
		  waitingThreads.add(Thread.currentThread());
		  Thread.sleep(waitDuration.toMilliseconds());
	  }finally{
		  waitingThreads.remove(Thread.currentThread());
	  }
  }

  /**
   * Interrupts waiting threads.
   */
  public void interruptWaiters() {
    for (Thread t : waitingThreads)
      t.interrupt();
  }
}
