# serial-mqtt-bridge
A bridge between virtual serial port devices such as eg. an Arduino and an MQTT broker

It scans trough all comports every now and then and connects to ports it has not seen before.
It connects with 9600 bps.

If the device's first character is "#" it assumes it follows protocol.
The content after # is currently only parsed as the name. Here would be a nice place to put other meta data such as protocol version and such.
The rest of the protocol is as follows:

| Line starts with  | Meaning | Example |
| ----------------- | ------- | ------- |
| $  | Message  | $coolSensorDevice/sensor 34.2 |
| @  | Persistent message  | @coolSensorDevice/sensor 34.2 |
| <  | A subscription. Anything on the message of this topic is sent to the device | <device/command |


It tries to connect to MQTT broker on 127.0.0.1

Name does not really work in stdout and subscriptions does not seem to work.