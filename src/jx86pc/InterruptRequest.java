/*
 *  InterruptRequest.java
 *  Joris van Rantwijk
 */

package jx86pc;

/**
 * An InterruptRequest object represents a specific input line
 * of an InterruptController.
 */
public interface InterruptRequest {

    /** A device calls the raise method to signal an interrupt. */
    void raise(boolean enable);

}

/* end */
