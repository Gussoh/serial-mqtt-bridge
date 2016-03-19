/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package mqtt.serial.bridge;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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

    private static CallbackConnection mqttConnection;
    
    private static final String PRESENCE_TOPIC = "mqtt-bridge-presence";
    
    private HashMap<String, List<SerialPortHandler>> subscriptions = new HashMap<>();
    
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
        new MQTTSerialBridge();
    }

    public MQTTSerialBridge() throws URISyntaxException, InterruptedException {
    
        MQTT mqtt = new MQTT();
        mqtt.setHost("tcp://localhost:1883");
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

                List<SerialPortHandler> subscribers = subscriptions.get(topic.toString());
                System.out.println("Incoming message: " + payload.toString() + " subscribers: " + subscribers.toString());
                if (subscribers != null) {
                    for (SerialPortHandler subscriber : subscribers) {
                        subscriber.onMqttMessage(topic.toString(), payload.toString());
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

        System.out.println("Got past connecting!");

        for (;;) {
            // List serial ports
            String[] portNames = SerialPortList.getPortNames();

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
                    
                    try {
                        new SerialPortHandler(portName, this);
                        handledSerialPorts.add(portName);
                    } catch (SerialPortTimeoutException e) {
                        System.out.println("No answer on port " + portName);
                        notHandledSerialPorts.add(portName);
                    } catch (Exception e) {
                        System.out.println("No good port: " + portName);
                        System.out.println("Got exception: " + e + ", " + e.getMessage());
                        notHandledSerialPorts.add(portName);
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

    void subscribe(SerialPortHandler subscriber, final String topic) {
        List<SerialPortHandler> subscribers = subscriptions.get(topic);
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
                System.out.println("Subscribed to topic: " + topic);
            }

            @Override
            public void onFailure(Throwable thrwbl) {
                thrwbl.printStackTrace();
            }
        });
    }

    void errorOccured(SerialPortHandler serialPortHandler) {
        handledSerialPorts.remove(serialPortHandler.getPortName());
    }
}
