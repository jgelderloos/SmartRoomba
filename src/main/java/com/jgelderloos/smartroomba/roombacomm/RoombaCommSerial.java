/*
 *  RoombaComm Serial Interface
 *
 *  Copyright (c) 2006 Tod E. Kurt, tod@todbot.com, ThingM
 *  Copyright (c) 2018 Jon Gelderloos
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General
 *  Public License along with this library; if not, write to the
 *  Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA  02111-1307  USA
 *
 */

package com.jgelderloos.smartroomba.roombacomm;

import com.jgelderloos.smartroomba.roomba.RoombaUtilities;
import com.jgelderloos.smartroomba.roomba.SensorData;
import gnu.io.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 *  The serial-port based implementation of RoombaComm.
 *  Handles both physical RS-232 ports, USB adapter ports like Keyspan 
 *  USA-19HS, and Bluetooth serial port profiles.
 * 
 * <p> Some code taken from processing.serial.Serial.  Thanks guys! </p>
 *
 *  The interaction model for setting the port and protocol and WaitForDSR parameters is as follows.
 *  <p>
 *  On creation, the class initializes the parameters, then tries to read .roomba_config.
 *  If it can read the config file and parse out the parameters, it sets the parameters to
 *  the values in the config file. Apps can read the current settings for display using methods
 *  on the class. Apps can override the settings by accepting user input and setting the
 *  parameters using methods on the class, or the connect() method. Parameters that are
 *  changed by the app are re-written in the config file, for use as defaults next run.
 *  Command-line apps can make these parameters optional, by using the defaults if the
 *  user doesn't specify them.  
 */
public class RoombaCommSerial extends RoombaComm implements SerialPortEventListener
{
    private static final Logger LOGGER = LogManager.getLogger(RoombaCommSerial.class);
    private RoombaUtilities roombaUtilities;
    private int rate = 115200;
    static final int databits = 8;
    static final int parity   = SerialPort.PARITY_NONE;
    static final int stopbits = SerialPort.STOPBITS_1;
    private String protocol = "OI";
    private Queue<SensorData> sensorDataQueue;

    /**
     * contains a list of all the ports
     * keys are port names (e.g. "/dev/usbserial1")
     * values are Boolean in-use indicator
     */
    static Map ports = null;

    /**
     * The time to wait in milliseconds after sending sensors command before
     * attempting to read
     */
    public static int updateSensorsPause = 400;

    /** The RXTX port object, normally you don't need access to this */
    public SerialPort port    = null;
    private String    portname = null;   //"/dev/cu.KeySerial1" for instance

    /** the serial input stream, normally you don't need access to this */
    public InputStream input;
    /** the serial output stream, normally you don't need access to this */
    public OutputStream output;

    /** 
     * RXTX bombs when flushing output sometimes, so by default do not
     * flush the output stream.  If the output is too buffered to be 
     * useful, do:  
     *  roombacomm.comm.flushOutput = true;
     * before using it and see if it works.
     */
    public boolean flushOutput = false;
    /** 
     * Some "virtual" serial ports like Bluetooth serial on Windows
     * return weird errors deep inside RXTX if an opened port is used
     * before the virtual COM port is ready.  One way to check that it 
     * is ready is to look for the DSR line going high.  
     * However, most simple, real serial ports do not do hardware handshaking
     * so never set DSR high.
     * Thus, if using Bluetooth serial on Windows, do:
     *  roombacomm.waitForDSR = true;
     * before using it and see if it works.
     */
    public boolean waitForDSR = false;		// Warning: public attribute - setting won't trigger config file write

    byte buffer[] = new byte[32768];
    int bufferLast;

    //int bufferSize = 26;  // how big before reset or event firing
    //boolean bufferUntil;
    //int bufferUntilByte;

    /**
     * Let you check to see if a port is in use by another Rooomba
     * before trying to use it.
     */
    // Dont think we need this
    /*
    public static boolean isPortInUse( String pname ) {
        Boolean inuse = (Boolean) ports.get( pname );
        if( inuse !=null ) {
            return inuse.booleanValue();
        }
        return false;
    }
    */

    public RoombaCommSerial() {
        super();
        // TODO: fix config file
        //readConfigFile();
        sensorDataQueue = new ConcurrentLinkedQueue<>();
        roombaUtilities = new RoombaUtilities();
    }

    /*
    public RoombaCommSerial(boolean autoupdate) {
        super(autoupdate);
        //readConfigFile();
        sensorDataQueue = new ConcurrentLinkedQueue<>();
    }

    public RoombaCommSerial(boolean autoupdate, int updateTime) {
        super(autoupdate, updateTime);
        //readConfigFile();
        sensorDataQueue = new ConcurrentLinkedQueue<>();
    }
    */
    public Queue<SensorData> getSensorDataQueue() {
        return sensorDataQueue;
    }

    /**
     * Connect to a serial port specified by portid
     * doesn't guarantee connection to Roomba, just to serial port
     * @param portid name of port, e.g. "/dev/cu.KeySerial1" or "COM3"
     * @return true if connect was successful, false otherwise
     */
    public boolean connect(String portid) {
        logmsg("connecting to port '"+portid+"'");
        portname = portid;

		//writeConfigFile(portname, protocol, waitForDSR?'Y':'N');

        connected = open_port();

        if (connected) {
            sensorsValid = false;
        }
        else {
            disconnect();
        }
        
        return connected;
    }

    /**
     * Disconnect from serial port
     */
    public void disconnect() {
        connected = false;

        try {
            // do io streams need to be closed first?
            if (input != null) {
                input.close();
            }
        } catch (IOException e) {
            LOGGER.error("Error closing input. ", e);
        }

        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException e) {
            LOGGER.error("Error closing output. ", e);
        }
        input = null;
        output = null;
    
        if (port != null) {
            port.close();  // close the port
        }

        port = null;
    }

    /**
     * subclassed.  FIXME: 
     */
    public boolean send(byte[] bytes) {
        try {
            output.write(bytes);
            if (flushOutput) {
                output.flush();   // hmm, not sure if a good idea
            }
        } catch (IOException e) { // null pointer or serial port dead
            LOGGER.error("Error writing to output. ", e);
        }
        return true;
    }

    /**
     * This will handle both ints, bytes and chars transparently.
     */
    // probably get rid of this so we dont have to worry about losing data in the bitwise &
    public boolean send(int b) {  // will also cover char or byte
        try {
            output.write(b & 0xff);  // for good measure do the &
            if (flushOutput) {
                output.flush();   // hmm, not sure if a good idea
            }
        } catch (IOException e) { // null pointer or serial port dead
            LOGGER.error("Error writing to output. ", e);
        }
        return true;
    }

    /**
     * toggles DD line via serial port DTR  (if available)
     */
    // TODO: not sure when this is needed
    public void wakeup() {
        port.setDTR(false);
        try {
            Thread.sleep(500);
        } catch (Exception e) {
            logmsg("Exception sleeping while wait for DSR");
        }
        port.setDTR(true);
    }
  
    /**
     * Update sensors.  Block for up to 1000 ms waiting for update
     * To use non-blocking, call sensors() and then poll sensorsValid()
     */
    // TODO: util
    public boolean updateSensors() {
    /*
        sensorsValid = false;
        allSensors();
        for(int i=0; i < 20; i++) {
            if( sensorsValid ) { 
                logmsg("updateSensors: sensorsValid!");
                break;
            }
            logmsg("updateSensors: pausing...");
            try {
                Thread.sleep(50);
            } catch (Exception e) {
                logmsg("Exception sleeping while wait for DSR");
            }
        }

        return sensorsValid;
    */
    return false;
    }

      
    /**
     * Update sensors.  Block for up to 1000 ms waiting for update
     * To use non-blocking, call sensors() and then poll sensorsValid()
     */
    /*
    public boolean updateSensors(int packetcode) {
        sensorsValid = false;
        sensors(packetcode);
        for(int i=0; i < 20; i++) {
            if( sensorsValid ) { 
                logmsg("updateSensors: sensorsValid!");
                break;
            }
            logmsg("updateSensors: pausing...");
            pause( 50 );
        }

        return sensorsValid;
    }
    */

    /**
     * called by serialEvent when we have enough bytes to make sensors valid
     */
    /*
    public void computeSensors() {
        sensorsValid = true;
        sensorsLastUpdateTime = System.currentTimeMillis();
        computeSafetyFault();
    }
    */

    /**
     * If this just hangs and never completes on Windows,
     * it may be because the DLL doesn't have its exec bit set.
     * Why the hell that'd be the case, who knows.
     * FIXME: deal more gracefully
     * (from processing.serial.Serial)
     */
    // keeping this around as a may me usefull to spit out info when connecting to a port fails
    public String[] listPorts() {
        Map ps = Collections.synchronizedMap(new LinkedHashMap());
        //Vector list = new Vector();
        try {
            //System.err.println("trying");
            Enumeration portList = CommPortIdentifier.getPortIdentifiers();
            //System.err.println("got port list");
            while (portList.hasMoreElements()) {
                CommPortIdentifier portId =
                    (CommPortIdentifier) portList.nextElement();
                logmsg("Found port: " + portId.getName());
                
                if (portId.getPortType() == CommPortIdentifier.PORT_SERIAL) {
                    String name = portId.getName();
                    //list.addElement(name);
                    Boolean state = (Boolean) ports.get( name );
                    if( state==null ) state = new Boolean(false);
                    ps.put( name, state );
                }
            }
        } catch (UnsatisfiedLinkError e) {
            //System.err.println("1");
            errorMessage("listPorts", e);
        } catch (Exception e) {
            //System.err.println("2");
            errorMessage("listPorts", e);
        }
        //System.err.println("move out");
        /*
        for( Enumeration e = list.elements(); e.hasMoreElements(); ) {
            String p = (String) e.nextElement();
            if( ! ports.containsKey( p ) ) {
                ports.put( p, new Boolean(false) );
            }
        }

        // DEBUG
        System.err.println("ports hashtable:");
        for( Enumeration e = ports.keys(); e.hasMoreElements(); ) {
            String p = (String) e.nextElement();
            Boolean b = (Boolean) ports.get(p);
            System.err.println("port:"+p+", inuse:"+b);
        }
        */
        ports = ps;
        String outgoing[] = 
            (String[]) new TreeSet(ports.keySet()).toArray(new String[0]);

        return outgoing;
    }


    public String getProtocol() {
		return protocol;
	}

	public void setProtocol(String protocol) {
		if (protocol.equals("SCI")) {
			rate = 57600;
		} else if (protocol.equals("OI")) {
			rate = 115200;
		}
		this.protocol = protocol;
        LOGGER.info("Protocol: {}", protocol);
		//writeConfigFile(portname, protocol, waitForDSR?'Y':'N');
	}

	public boolean isWaitForDSR() {
		return waitForDSR;
	}

	public void setWaitForDSR(boolean waitForDSR) {
		this.waitForDSR = waitForDSR;
		//writeConfigFile(portname, protocol, waitForDSR?'Y':'N');
	}

	public String getPortname() {
		return portname;
	}
	
	public void setPortname(String p) {
		portname = p;
        LOGGER.info("Port: {}", portname);
		//writeConfigFile(portname, protocol, waitForDSR?'Y':'N');

	}

   // -------------------------------------------------------------
    // below only used internally to this class
    // -------------------------------------------------------------

	/**
     * internal method, used by connect()
     * FIXME: make it faile more gracefully, recognize bad port
     */
    private boolean open_port() {
        boolean success = false;
        try {
            Enumeration portList = CommPortIdentifier.getPortIdentifiers();
            while (portList.hasMoreElements()) {
                CommPortIdentifier portId = (CommPortIdentifier) portList.nextElement();
                if (portId.getPortType() == CommPortIdentifier.PORT_SERIAL) {
                    LOGGER.debug("Found port: {}", portId.getName());
                    if (portId.getName().equals(portname)) {
                        LOGGER.debug("Open port: {}", portId.getName());
                        port = (SerialPort)portId.open("roomba serial", 2000);
                        input  = port.getInputStream();
                        output = port.getOutputStream();
                        port.setSerialPortParams(rate,databits,stopbits,parity);
                        port.addEventListener(this);
                        port.notifyOnDataAvailable(true);
                        LOGGER.debug("Port {} open successfully", portname);

                        if( waitForDSR ) {
                            int i=40;
                            while( !port.isDSR() && i-- != 0) {
                                LOGGER.debug("DSR not ready yet");
                                //pause(150); // 150*40 = 6 seconds
                                roombaUtilities.sleep(150, "waiting for DSR in open_port");
                            }
                            success = port.isDSR();
                        } else {
                            success = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Exception caught in open_port. ", e);
            port = null;
            input = null;
            output = null;
        }
                        
        return success;
    }

    /**
     * callback for SerialPortEventListener
     * (from processing.serial.Serial)
     */
    synchronized public void serialEvent(SerialPortEvent serialEvent) {
        try {
            LOGGER.debug("SerialEvent: {}, available: {}", serialEvent, input.available());
            if (serialEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
                logmsg("data available");
                LOGGER.debug("Data available");
                // input.available does not always add up to the total bytes being read, sometime more data becomes available
                // during the while loop
                while (input.available() > 0) {
                    buffer[bufferLast++] = (byte) input.read();
                    if( bufferLast == super.readRequestLength) {
                        bufferLast = 0;
                        System.arraycopy(buffer, 0, sensor_bytes, 0, super.readRequestLength);
                        // TODO: possibly think of a way to init SensorData when we get the first packet so
                        // the timestamp is more accurate
                        SensorData sensorData = new SensorData(buffer, super.readRequestLength);
                        sensorDataQueue.add(sensorData);
                        //computeSensors();
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Exception caught in serialEvent. ", e);
            throw new RuntimeException("Error inside serialEvent");
        }
    }
    
    /**
     * Write a config file with current settings
     */
    // TODO: I dont think we need a config file
    /*
    private void writeConfigFile(String port, String protocol, char waitForDSR)
    {
    	try {
    		FileWriter f = new FileWriter(".roomba_config", false);
        	BufferedWriter w = new BufferedWriter(f); // create file
        	w.write(port);
        	w.newLine();
        	w.write(protocol);
        	w.newLine();
        	w.write(waitForDSR);
        	w.newLine();
        	w.close();
        	f.close();
    	} catch (IOException e) {
    		logmsg("unable to write .roomba_config " + e);
    	}
    }
    private void readConfigFile()
    {
    	try {
    		FileReader f = new FileReader(".roomba_config");
    		BufferedReader r = new BufferedReader(f);
    		portname = r.readLine();
    		protocol = r.readLine();
    		if (protocol.equals("OI")) {
    			rate = 115200;
    		}
    		waitForDSR = r.readLine().equals("Y")?true:false;
    		logmsg("read config port: " + port + " protocol: " + protocol + " waitDSR: " + waitForDSR);    		
    	} catch (IOException e) {
    		logmsg("unable to read .roomba_config " + e);
    	}
    }
    */

    /**
     * Returns the number of bytes that have been read from serial
     * and are waiting to be dealt with by the user.
     * (from processing.serial.Serial)
     *
    private int available() {
        return (bufferLast - bufferIndex);
    }

    /**
     * Return a byte array of anything that's in the serial buffer.
     * Not particularly memory/speed efficient, because it creates
     * a byte array on each read, but it's easier to use than
     * readBytes(byte b[]) (see below).
     * (from processing.serial.Serial)
     *
    private byte[] readBytes() {
        if (bufferIndex == bufferLast) return null;

        synchronized (buffer) {
            int length = bufferLast - bufferIndex;
            byte outgoing[] = new byte[length];
            System.arraycopy(buffer, bufferIndex, outgoing, 0, length);

            bufferIndex = 0;  // rewind
            bufferLast = 0;
            return outgoing;
        }
    }
  
    /**
     * Grab whatever is in the serial buffer, and stuff it into a
     * byte buffer passed in by the user. This is more memory/time
     * efficient than readBytes() returning a byte[] array.
     *
     * Returns an int for how many bytes were read. If more bytes
     * are available than can fit into the byte array, only those
     * that will fit are read.
     * (from processing.serial.Serial)
     *
    public int readBytes(byte outgoing[]) {
        if (bufferIndex == bufferLast) return 0;

        synchronized (buffer) {
            int length = bufferLast - bufferIndex;
            if (length > outgoing.length) length = outgoing.length;
            System.arraycopy(buffer, bufferIndex, outgoing, 0, length);

            bufferIndex += length;
            if (bufferIndex == bufferLast) {
                bufferIndex = 0;  // rewind
                bufferLast = 0;
            }
            return length;
        }
    }
    */

}
