# serial-mqtt-bridge
A bridge between virtual serial port devices such as eg. an Arduino and an MQTT broker

It scans trough all serial ports every now and then and connects to ports it is currently not connected to.
It connects with 9600 bps.

If a device follows the format then it starts parsing. With protocol version 1 the presence message must be sent within 25 seconds. With version 2 the presence message must be sent at least every 25 seconds. This helps with removing stale connections.
Example: ###My device,2###
Name: My device,
Protocol version: 2

The rest of the protocol is as follows (Version 1 & 2):

| Line starts with  | Meaning | Example |
| ----------------- | ------- | ------- |
| ###  | Presence message. Protocol metadata | ###sensorthing,2### |
| $  | Message  | $coolSensorDevice/sensor##34.2### |
| @  | Persistent message  | @coolSensorDevice/sensor##34.2### |
| <  | A subscription. Anything on the message of this topic is sent to the device | <device/command### |

Divider between topic and message is "##".
All lines ends with "###". This is just a super simple way to have some kind of sanity check on the data. If it does not end with ### it is not a valid message.


It tries to connect to MQTT broker on 127.0.0.1 unless an argument is added like: java -jar mqttSerialbridge.jar tcp://example.com:1883
