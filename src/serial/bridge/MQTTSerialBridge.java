/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package serial.bridge;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import jssc.SerialPortList;
import jssc.SerialPortTimeoutException;
import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.UTF8Buffer;
import org.fusesource.mqtt.client.Callback;
import org.fusesource.mqtt.client.CallbackConnection;
import org.fusesource.mqtt.client.Listener;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

/**
 *
 * @author Gussoh
 */
public class MQTTSerialBridge {

    static String mqttUrl = "tcp://localhost:1883";
    
    private static CallbackConnection mqttConnection;
    private Semaphore waitForMqttConnect = new Semaphore(0);
    
    private static final String PRESENCE_TOPIC = "mqtt-bridge-presence";
    
    private HashMap<String, List<SerialConnection>> subscriptions = new HashMap<>();
    
    /**
     * Things which are following the protocol
     */
    private Set<String> handledSerialPorts = new HashSet<>();
    /**
     * Other things. Don't connect again.
     * This will never try actual, real com ports twice which is kind of bad but we only support virtual COM-ports
     */
    private Set<String> notHandledSerialPorts = new HashSet<>();
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            mqttUrl = args[0];
        }
        new MQTTSerialBridge();
    }

    public MQTTSerialBridge() throws URISyntaxException, InterruptedException {
    
        MQTT mqtt = new MQTT();
        mqtt.setHost(mqttUrl);
        mqtt.setCleanSession(true);
        mqtt.setWillTopic(PRESENCE_TOPIC);
        mqtt.setWillMessage("disconnected");
        mqtt.setWillRetain(true);
        mqtt.setClientId("mqtt-bridge " + new Random().nextInt());

        mqttConnection = mqtt.callbackConnection();

        mqttConnection.listener(new Listener() {

            @Override
            public void onConnected() {
                mqttConnection.publish(PRESENCE_TOPIC, "connected".getBytes(), QoS.AT_MOST_ONCE, true, new Callback<Void>() {

                    @Override
                    public void onSuccess(Void t) {
                        System.out.println("Connected to MQTT");
                        waitForMqttConnect.release();
                    }

                    @Override
                    public void onFailure(Throwable thrwbl) {}
                });
            }

            @Override
            public void onDisconnected() {
                System.out.println("Disconnected from MQTT");
            }

            @Override
            public void onPublish(UTF8Buffer topic, Buffer payload, Runnable ack) {

                List<SerialConnection> subscribers = subscriptions.get(topic.toString());
                System.out.println("Incoming message: " + payload.toString() + " subscribers: " + subscribers);
                if (subscribers != null) {
                    for (SerialConnection subscriber : subscribers) {
                        subscriber.onMqttMessage(topic.toString(), payload.ascii().toString());
                    }
                }
                
                ack.run();
            }

            @Override
            public void onFailure(Throwable thrwbl) {
                thrwbl.printStackTrace();
            }
        });

        System.out.println("Connecting to mqtt!");

        mqttConnection.connect(new Callback<Void>() {

            @Override
            public void onSuccess(Void t) {
                System.out.println("On success connect!");
            }

            @Override
            public void onFailure(Throwable thrwbl) {
                System.out.println("On fail connect: " + thrwbl.getMessage());
                thrwbl.printStackTrace();
            }
        });

        
        if (waitForMqttConnect.availablePermits() > 0) {
            System.out.println("Waiting for MQTT connection...");
        }
        waitForMqttConnect.acquire();
        
        for (;;) {
            // List serial ports
            
            String[] portNames;
            if (System.getProperty("os.name").toLowerCase().contains("os x")) {
                portNames = SerialPortList.getPortNames("/dev/", Pattern.compile("(tty.(serial|usbserial|usbmodem).*)|cu.*"));
            } else {
                portNames = SerialPortList.getPortNames();
            }
            
            

            System.out.println("Serial ports: " + Arrays.toString(portNames));

            if (portNames.length == 0) {
                System.out.println("No Comm ports!");
            } else {
                Set<String> currentPorts = new HashSet<>();
                for (String portName : portNames) {
                    currentPorts.add(portName);
                    
                    if (notHandledSerialPorts.contains(portName) || handledSerialPorts.contains(portName)) {
                        continue;
                    }
                    
                    int nrOfAttempts = 3;
                    for (int i = 0; i < nrOfAttempts; i++) { 
                        try {
                            new SerialConnection(portName, this);
                            handledSerialPorts.add(portName);
                        } catch (SerialPortTimeoutException e) {
                            System.out.println("No answer on port " + portName);
                            notHandledSerialPorts.add(portName);
                            break;
                        } catch (Exception e) {
                            System.out.println("No good port: " + portName);
                            System.out.println("Got exception: " + e + ", " + e.getMessage());
                            if (i == nrOfAttempts - 1) { // last attempt
                                notHandledSerialPorts.add(portName);
                            } else {
                                System.out.println("Trying same port again...");
                                Thread.sleep(2000);
                            }
                        }
                    }
                }
                
                notHandledSerialPorts.retainAll(currentPorts); // remove all disconnected serial ports which are no longer connected.
            }
            
            Thread.sleep(15000);
        }
    }
    
    public static CallbackConnection getMqttConnection() {
        return mqttConnection;
    }

    void subscribe(final SerialConnection subscriber, final String topic) {
        List<SerialConnection> subscribers = subscriptions.get(topic);
        if (subscribers == null) {
            subscribers = new ArrayList<>();
            subscribers.add(subscriber);
            subscriptions.put(topic, subscribers);
        } else {
            subscribers.add(subscriber);
        }
        
        getMqttConnection().subscribe(new Topic[]{new Topic(topic, QoS.AT_MOST_ONCE)}, new Callback<byte[]>() {

            @Override
            public void onSuccess(byte[] t) {
                System.out.println(subscriber.getName() + " subscribed to topic: " + topic);
            }

            @Override
            public void onFailure(Throwable thrwbl) {
                thrwbl.printStackTrace();
            }
        });
    }

    void errorOccured(SerialConnection serialPortHandler) {
        handledSerialPorts.remove(serialPortHandler.getPortName());
    }
}
