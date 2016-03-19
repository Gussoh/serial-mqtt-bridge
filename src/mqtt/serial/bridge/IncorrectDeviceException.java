/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package mqtt.serial.bridge;

/**
 *
 * @author Gussoh
 */
public class IncorrectDeviceException extends Exception {

    public IncorrectDeviceException(String first) {
        super(first);
    }
    
}
