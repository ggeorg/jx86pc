/*
 *  ExternalInputEvent.java
 *  Joris van Rantwijk
 */

package jx86pc.v1;
import java.util.EventObject;

/**
 * An ExternalInputEvent encapsulates data (key presses, serial port data, etc)
 * entering the simulation from an external source.
 */
public class ExternalInputEvent extends EventObject
{

    public long timestamp;

    public ExternalInputEvent(Object source)
    {
        super(source);
    }

}

/* end */
