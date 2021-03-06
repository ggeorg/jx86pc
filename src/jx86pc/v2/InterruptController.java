/*
 *  InterruptController.java
 *  Joris van Rantwijk
 */

package jx86pc.v2;

/**
 * An implementation of InterruptController accumulates and serializes interrupt
 * signals from various sources and communicates them to the CPU.
 */
public interface InterruptController {

    /**
     * Called by the {@link Cpu} to poll for pending interrupt requests.
     * <p>
     * Returns a vector number, or -1 if no interrupt was pending. A call to this
     * method with non-negative return value also serves as an acknowledgement of
     * the interrupt by the {@link Cpu}.
     * <p>
     * Thus, the complete INTR/INTA interface and transfer of the interrupt vector
     * number is implemented as a single call to this method.
     */
    int getPendingInterrupt();

}

/* end */
