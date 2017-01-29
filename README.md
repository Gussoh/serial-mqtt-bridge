# serial-mqtt-bridge
A bridge between virtual serial port devices such as eg. an Arduino and an MQTT broker

It scans trough all comports every now and then and connects to ports it has not seen before.
It connects with 9600 bps.

If the device's first characters are "###" it assumes it follows protocol and starts parsing. 
The format of the first line is this: First "###". Until the first comma the content is parsed as the units name. After the next commas is the protocol version.
Example: ###My device,1###
Name: My device,
Protocol version: 1 (1 is the only version currently available)

The rest of the protocol is as follows (Version 1):

| Line starts with  | Meaning | Example |
| ----------------- | ------- | ------- |
| ###  | First line and protocol metadata | ###sensorthing,1### |
| $  | Message  | $coolSensorDevice/sensor##34.2### |
| @  | Persistent message  | @coolSensorDevice/sensor##34.2### |
| <  | A subscription. Anything on the message of this topic is sent to the device | <device/command### |

Divider between topic and message is "##".
All lines ends with "###". This is just a super simple way to have some kind of sanity check on the data. If it does not end with ### it is not a valid message.


It tries to connect to MQTT broker on 127.0.0.1 unless an argument is added like: java -jar mqttSerialbridge.jar tcp://example.com:1883