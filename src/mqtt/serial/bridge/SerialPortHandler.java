/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package mqtt.serial.bridge;

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
public class SerialPortHandler {

    final String portName;
    final MQTTSerialBridge bridge;
    final SerialPort serialPort;
    String name = null;
    
    StringBuilder incomingLine = new StringBuilder();
    
    public SerialPortHandler(String portName, final MQTTSerialBridge bridge) throws SerialPortException, SerialPortTimeoutException, IncorrectDeviceException {
        this.portName = portName;
        this.bridge = bridge;
        serialPort = new SerialPort(portName);
        serialPort.openPort();
            
        serialPort.setParams(SerialPort.BAUDRATE_9600,
                SerialPort.DATABITS_8,
                SerialPort.STOPBITS_1,
                SerialPort.PARITY_NONE);

        serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
        //serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_IN | SerialPort.FLOWCONTROL_RTSCTS_OUT);

        System.out.println("Connecting to " + portName);
        
        String firstChar = serialPort.readString(1, 5000);
        if (!firstChar.equalsIgnoreCase("#")) { // Make sure it is one of our arduinos
            String totalData = firstChar + serialPort.readString();
            serialPort.closePort();
            throw new IncorrectDeviceException(totalData);
        }
        
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
                                    incomingLine = new StringBuilder();
                                    parseLine(line);   
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
                            Logger.getLogger(SerialPortHandler.class.getName()).log(Level.SEVERE, null, ex1);
                        }
                        System.err.println("Error in receiving string from: " + name + "\n" + ex);
                        
                        bridge.errorOccured(SerialPortHandler.this);
                    }
                }
            }
        }, SerialPort.MASK_RXCHAR);
    }
    
    private void parseLine(final String line) {
        if (name == null) {
            name = line;
            System.out.println("Serial port got name: " + name);
            return;
        }
        System.out.println(name + ": " + line);

        if (line.startsWith("<")) { // new subscription
            bridge.subscribe(this, line.substring(1));
        } else if (line.startsWith("@") || line.startsWith("$")) { // mqtt! @ is persist, $ is not persised
            String[] split = line.substring(1).split(" "); // remove indicator char and separate between topic and message
            if (split.length != 2) {
                System.out.println("Incorrect format. Two sections was: " + split.length);
            }
            
            String topic = split[0].replace("$time", new Date().toString());
            String message = split[1].replace("$time", new Date().toString());
            
            bridge.getMqttConnection().publish(topic, message.getBytes(), QoS.AT_MOST_ONCE, line.startsWith("@"), new Callback<Void>() {

                @Override
                public void onSuccess(Void t) {
                    System.out.println("-- SENT " + (line.startsWith("@") ? "persistent " : "") + "TO MQTT  --");
                }

                @Override
                public void onFailure(Throwable thrwbl) {
                    System.err.println("Could not send to MQTT:\n" + thrwbl);
                }
            });
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
                Logger.getLogger(SerialPortHandler.class.getName()).log(Level.SEVERE, null, ex1);
            }
            System.err.println("Error in sending string from to: " + name + "\n" + ex);

            bridge.errorOccured(this);
        }
    }
    
    public void stop() {
        try {
            serialPort.closePort();
        } catch (SerialPortException ex) {
            Logger.getLogger(SerialPortHandler.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    String getPortName() {
        return portName;
    }
}
