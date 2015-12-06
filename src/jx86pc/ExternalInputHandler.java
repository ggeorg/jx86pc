/*
 *  ExternalInputHandler.java
 *  Joris van Rantwijk
 */

package jx86pc;

/**
 * An ExternalInputHandler accepts external input events (key presses,
 * mouse clicks, serial port data) and feeds them into the simulator.
 */
public interface ExternalInputHandler
{

    void handleInput(ExternalInputEvent evt);

}

/* end */
