/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package serial.bridge;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;
import jssc.SerialPortTimeoutException;
import org.fusesource.mqtt.client.Callback;
import org.fusesource.mqtt.client.QoS;

/**
 *
 * @author Gussoh
 */
public class SerialConnection {

    final String portName;
    final MQTTSerialBridge bridge;
    final SerialPort serialPort;
    private String name = null;
    
    StringBuilder incomingLine = new StringBuilder();
    
    int version = 0;
    long lastPresence = System.currentTimeMillis();
    
    public SerialConnection(String portName, final MQTTSerialBridge bridge) throws SerialPortException, SerialPortTimeoutException, IncorrectDeviceException {
        this.portName = portName;
        this.bridge = bridge;
        serialPort = new SerialPort(portName);
        
        System.out.println("Connecting to " + portName);
        serialPort.openPort();
        serialPort.setParams(SerialPort.BAUDRATE_9600,
                SerialPort.DATABITS_8,
                SerialPort.STOPBITS_1,
                SerialPort.PARITY_NONE);
        serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_IN | SerialPort.FLOWCONTROL_RTSCTS_OUT);
        serialPort.closePort(); // try close and open again to see if we can get in sync.. dont know why it doesnt always work.
        serialPort.openPort();
        
        serialPort.addEventListener(new SerialPortEventListener() {
                
            @Override
            public void serialEvent(SerialPortEvent event) {
                if(event.isRXCHAR() && event.getEventValue() > 0) {
                    try {
                        String receivedData = serialPort.readString(event.getEventValue());
                        
                        for (int i = 0; i < receivedData.length(); i++) {
                            if (receivedData.charAt(i) == '\n') {
                                if (incomingLine.length() > 0) {
                                    String line = incomingLine.toString();
                                    parseLine(line);
                                    incomingLine = new StringBuilder();
                                }
                            } else {
                                incomingLine.append(receivedData.charAt(i));
                            }
                        }
                    }
                    catch (Exception ex) {
                        try {
                            serialPort.closePort();
                        } catch (SerialPortException ex1) {
                            Logger.getLogger(SerialConnection.class.getName()).log(Level.SEVERE, null, ex1);
                        }
                        System.err.println("Error in receiving string from: " + name + "\n" + ex);
                        
                        bridge.errorOccured(SerialConnection.this);
                    }
                }
            }
        }, SerialPort.MASK_RXCHAR);
        
        
    }
    
    private void parseLine(String line) throws ProtocolMalformedException {
        final String time = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX").withZone(ZoneOffset.UTC).format(Instant.now());
        
        line = line.trim();
        
        System.out.print(time + " " + portName + " - " + name + ": ");
        System.out.println(line);
        
        if (line.startsWith("###") && line.endsWith("###")) { // looking for presence message
            line = line.substring(3, line.length() - 3); // remove the last ###
            String[] parts = line.split(",");
            if (parts.length < 2) {
                throw new ProtocolMalformedException("Missing name or protocol identifier");
            }
            String versionStr = parts[1];
            try {
            version = Integer.parseInt(versionStr);
            } catch (NumberFormatException e) {
                throw new ProtocolMalformedException("Can not understand protocol version: " + versionStr);
            }
            if (version != 1 && version != 2) {
                throw new ProtocolMalformedException("Can not understand protocol version: " + versionStr);
            }
            name = parts[0];
            lastPresence = System.currentTimeMillis();
            return;
        }
        
        if (version == 0) {
            System.out.println("-- Ignore message. Waiting for presence message.");
            return;
        }
        
        if (line.endsWith("###")) {
            line = line.substring(0, line.length() - 3); // remove the last ###
            if (line.startsWith("<")) { // new subscription
                bridge.subscribe(this, line.substring(1));
            } else if (line.startsWith("@") || line.startsWith("$")) { // mqtt! @ is persist, $ is not persised
                String[] split = line.substring(1).split("##"); // remove indicator char and separate between topic and message
                if (split.length != 2) {
                    System.out.println("Incorrect format. Two sections was: " + split.length);
                }

                String topic = split[0].replace("$time", time);
                String message = split[1].replace("$time", time);

                bridge.getMqttConnection().publish(topic, message.getBytes(), QoS.AT_MOST_ONCE, line.startsWith("@"), new Callback<Void>() {

                    @Override
                    public void onSuccess(Void t) {
                        System.out.println(time + " -- SENT TO MQTT  --");
                    }

                    @Override
                    public void onFailure(Throwable thrwbl) {
                        System.err.println(time + " Could not send to MQTT:\n" + thrwbl);
                        System.err.println("Can't solve the problem. Quitting and expecting a restart.");
                        System.exit(3);
                    }
                });
            }
        }
    }
    
    public void onMqttMessage(String topic, String message) {
        System.out.println("onPublish sent to: " + name + " topic: " + topic + ", payload: " + message);
        
        try {
            serialPort.writeString(message);
        } catch (SerialPortException ex) {
            try {
                serialPort.closePort();
            } catch (SerialPortException ex1) {
                Logger.getLogger(SerialConnection.class.getName()).log(Level.SEVERE, null, ex1);
            }
            System.err.println("Error in sending string from to: " + name + "\n" + ex);

            bridge.errorOccured(this);
        }
    }
    
    public void stop() {
        try {
            serialPort.closePort();
        } catch (SerialPortException ex) {
            Logger.getLogger(SerialConnection.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    String getPortName() {
        return portName;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return getName();
    }

    public int getVersion() {
        return version;
    }

    public long getLastPresence() {
        return lastPresence;
    }
    
    /**
     * invoked if presence has not been set for a long time in version 2 of protocol
     */
    public void onTimeout() {
        try {
            serialPort.closePort();
        } catch (SerialPortException ex1) {
            Logger.getLogger(SerialConnection.class.getName()).log(Level.SEVERE, null, ex1);
        }
        System.err.println("No presence for a long time from: " + name + " - " + serialPort.getPortName());

        bridge.errorOccured(SerialConnection.this);
    }
}
