/*
 * Copyright © 2017, Finium Solutions, All Rights Reserved
 * 
 * USBConnectionClient.java
 * Modification History
 * *************************************************************
 * Date				Author		Comment
 * Feb 09, 2017		Venkaiah Chowdary Koneru		Created
 * *************************************************************
 */
package com.finium.core.drivers.tspl.connection;

import com.finium.core.drivers.tspl.exceptions.PrinterException;
import com.finium.core.drivers.tspl.listeners.ClientListener;
import com.finium.core.drivers.tspl.listeners.DataListener;
import lombok.extern.slf4j.Slf4j;

import javax.usb.*;
import javax.usb.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * USB based client communication implementation for TSPL2 device
 *
 * @author Venkaiah Chowdary Koneru
 */
@Slf4j
public class USBConnectionClient implements TSPLConnectionClient, UsbDeviceListener {
    private List<ClientListener> clientListeners = new ArrayList<ClientListener>();
    private List<DataListener> dataListeners = new ArrayList<DataListener>();
    private ExecutorService executorService = Executors.newCachedThreadPool();
    private ExecutorService commExecutorService = Executors.newSingleThreadExecutor();
    private short tscVendorId = 0x1203;
    private short tscProductId = 0x0172;
    private short outPipeAddress = -126;
    private short inPipeAddress = 1;
    private UsbDevice usbDevice;
    private UsbInterface usbInterface;
    private UsbPipe writePipe;
    private UsbPipe readPipe;
    private boolean isConnected = Boolean.FALSE;

    /**
     * @param tscVendorId
     * @param tscProductId
     */
    public USBConnectionClient(short tscVendorId, short tscProductId) {
        this.tscVendorId = tscVendorId;
        this.tscProductId = tscProductId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        // Get the USB services and dump information about them
        try {
            final UsbServices services = UsbHostManager.getUsbServices();

            log.info("USB Service Implementation: {}", services.getImpDescription());
            log.info("Implementation version: {}", services.getImpVersion());
            log.info("Service API version: {}", services.getApiVersion());

            // find the USB device from the root USB hub
            usbDevice = findDevice(services.getRootUsbHub(), tscVendorId, tscProductId);

            findAndClaimInterface();
        } catch (UsbException e) {
            log.error("USBException ", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addClientListener(ClientListener listener) {
        this.clientListeners.add(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeClientListener(ClientListener listener) {
        this.clientListeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDataListener(DataListener listener) {
        this.dataListeners.add(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeDataListener(DataListener listener) {
        this.dataListeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void connect() {
        if (isConnected)
            return;

        if (usbInterface == null) {
            throw new PrinterException("Interface has to be claimed before attempting connection");
        }

        readPipe = getReadPipe();
        writePipe = getWritePipe();

        try {
            readPipe.open();
            writePipe.open();
            isConnected = true;

            notifyConnection();
        } catch (UsbException e) {
            log.error("USBException ", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void disconnect() {
        if (!isConnected)
            return;

        try {
            writePipe.close();
            readPipe.close();

            isConnected = false;
            notifyConnectionLost();
        } catch (UsbException e) {
            log.error("USBException ", e);
        }
    }

    @Override
    public void shutdown() {
        try {
            usbInterface.release();
            usbInterface = null;
        } catch (UsbException e) {
            log.error("", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Note that submissions (except interrupt and bulk in-direction)
     * will not block indefinitely, they will complete or fail within
     * a finite amount of time.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public void send(String tsplMessage) {
        send(tsplMessage.getBytes(US_ASCII));
    }

    @Override
    public void send(byte[] message) {
        if (!isConnected) {
            throw new PrinterException("Printer is not connected");
        }

        try {
            writePipe.syncSubmit(message);
        } catch (UsbException e) {
            log.error("Exception submit", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void usbDeviceDetached(UsbDeviceEvent event) {
        clientListeners.forEach((ClientListener listener) -> {
            executorService.execute(() -> listener.connectionLost(USBConnectionClient.this));
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void errorEventOccurred(UsbDeviceErrorEvent event) {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dataEventOccurred(UsbDeviceDataEvent event) {

    }

    /**
     * Retrieves the desired USB device from the given root hub based on the
     * vendorId and productId.
     *
     * @param hub       the root hub services object
     * @param vendorId  USB device vendor id
     * @param productId USB device product id
     * @return matched USBDevice object
     */
    private UsbDevice findDevice(UsbHub hub, short vendorId, short productId) {
        for (UsbDevice device : (List<UsbDevice>) hub.getAttachedUsbDevices()) {
            UsbDeviceDescriptor desc = device.getUsbDeviceDescriptor();
            if (desc.idVendor() == vendorId && desc.idProduct() == productId) return device;
            if (device.isUsbHub()) {
                device = findDevice((UsbHub) device, vendorId, productId);
                if (device != null) return device;
            }
        }
        return null;
    }

    /**
     * Finds the USBDevice's interface and claims the interface
     */
    private void findAndClaimInterface() {
        UsbConfiguration configuration = usbDevice.getActiveUsbConfiguration();
        usbInterface = configuration.getUsbInterface((byte) 0);
        try {
            usbInterface.claim(new UsbInterfacePolicy() {
                @Override
                public boolean forceClaim(UsbInterface usbInterface) {
                    return true;
                }
            });
        } catch (UsbException e) {
            log.error("", e);
        }

        ((List<UsbEndpoint>) usbInterface.getUsbEndpoints()).forEach(p -> {
            log.info("Interface Direction: {}, Interface Address: {}",
                    p.getDirection(), p.getUsbEndpointDescriptor().bEndpointAddress());
        });
    }

    /**
     * gets the OUT type USBPipe
     *
     * @return the USBPipe
     */
    private UsbPipe getReadPipe() {
        if (!isConnected) {
            throw new PrinterException("Printer is not connected");
        }

        UsbPipe localReadPipe = usbInterface.getUsbEndpoint((byte) outPipeAddress).getUsbPipe();
        localReadPipe.addUsbPipeListener(new UsbPipeListener() {
            @Override
            public void errorEventOccurred(UsbPipeErrorEvent event) {

            }

            @Override
            public void dataEventOccurred(UsbPipeDataEvent event) {
                notifyReadDataEvent(event);
            }
        });
        return localReadPipe;
    }

    /**
     * gets the IN type USBPipe
     *
     * @return USBPipe
     */
    private UsbPipe getWritePipe() {
        if (!isConnected) {
            throw new PrinterException("Printer is not connected");
        }

        UsbPipe localWritePipe = usbInterface.getUsbEndpoint((byte) inPipeAddress).getUsbPipe();
        localWritePipe.addUsbPipeListener(new UsbPipeListener() {
            @Override
            public void errorEventOccurred(UsbPipeErrorEvent event) {

            }

            @Override
            public void dataEventOccurred(UsbPipeDataEvent event) {
                notifyWriteDataEvent(event);
            }
        });
        return localWritePipe;
    }

    /**
     * Notifies all the dataListeners about the received message.
     *
     * @param dataEvent
     */
    private void notifyReadDataEvent(UsbPipeDataEvent dataEvent) {
        dataListeners.forEach((DataListener dataListener) -> {
            executorService.execute(() -> {
                dataListener.messageReceived(new String(dataEvent.getData(), US_ASCII));
            });
        });
    }

    /**
     * Notifies all the dataListeners about the submitted message.
     *
     * @param dataEvent
     */
    private void notifyWriteDataEvent(UsbPipeDataEvent dataEvent) {
        dataListeners.forEach((DataListener dataListener) -> {
            executorService.execute(() -> {
                dataListener.messageSent(new String(dataEvent.getData(), US_ASCII));
            });
        });
    }

    /**
     * All the client listeners will be informed about the successful
     * connection establishment to the TSPL2 device.
     */
    private void notifyConnection() {
        clientListeners.forEach((listener) -> {
            listener.connectionEstablished(this);
        });
    }

    /**
     * All the client listeners will be informed about loss of connection
     * to the TSPL2 device.
     */
    private void notifyConnectionLost() {
        clientListeners.forEach((listener) -> {
            listener.connectionLost(this);
        });
    }

    /**
     * All the client listeners will be informed about persistent connection failure
     * to the TSPL2 device.
     */
    private void notifyConnectionFailed() {
        clientListeners.forEach((listener) -> {
            //listener.connectionIsFailing(this);
        });
    }
}