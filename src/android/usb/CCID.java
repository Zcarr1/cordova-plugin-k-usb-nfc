package in.co.indusnet.cordova.plugins.nfc.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.nio.charset.StandardCharsets;

import in.co.indusnet.cordova.plugins.nfc.diagnostic.CCIDDescriptor;

public class CCID implements Closeable {

    private static final String TAG = "KNFC";
    private static final ScheduledExecutorService StateListenerPool = Executors.newScheduledThreadPool(1);

    public static enum SlotStatus {
        Active,
        Inactive,
        Missing
    }

    public static class Response {
        public byte param;
        public byte[] data;
    }

    public static boolean isCCIDCompliant(UsbDevice usbDevice) {
        if (usbDevice.getDeviceClass() == UsbConstants.USB_CLASS_CSCID) {
            return true;
        } else if (usbDevice.getDeviceClass() == UsbConstants.USB_CLASS_PER_INTERFACE) {
            for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                if (usbDevice.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_CSCID) {
                    return true;
                }
            }
        }
        return false;
    }

    //Input property
    private UsbDevice usbDevice;
    private UsbManager usbManager;
    private UsbInterface usbInterface;

    //connection
    private boolean pinPad;
    private boolean supportApdu;
    private boolean autoInit;
    private UsbDeviceConnection usbConnection;

    //USB streams
    private UsbEndpoint usbOut;
    private UsbEndpoint usbIn;
    private UsbEndpoint usbInterrupt;

    //State properties
    private int sequence;
    private ScheduledFuture stateRequester;

    //callback
    private CardCallback callback;

    public CardCallback getCallback()
    {
        return callback;
    }

    public void setCallback(CardCallback value)
    {
        callback = value;
        setupStateListener();
    }

    public synchronized boolean isOpen() {
        return usbConnection != null;
    }

    public boolean hasPinPad() {
        return pinPad;
    }

    public CCID(UsbManager usbManager, UsbDevice usbDevice)
    {
        this.usbManager = usbManager;
        this.usbDevice = usbDevice;
    }

    public CCID(UsbManager usbManager, UsbDevice usbDevice, UsbInterface usbInterface) {
        this(usbManager, usbDevice);
        this.usbInterface = usbInterface;
    }

    public boolean isCCIDCompliant() {
        return CCID.isCCIDCompliant(usbDevice);
    }

    public synchronized void open() throws IOException {
        if (usbDevice == null) throw new IllegalArgumentException("Device can't be null");

        if (usbInterface == null) {
            for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                UsbInterface usbIf = usbDevice.getInterface(i);
                if (usbIf.getInterfaceClass() == UsbConstants.USB_CLASS_CSCID) {
                    usbInterface = usbIf;
                }
            }
            if (usbInterface == null)
                throw new IllegalStateException("The device hasn't a smart card reader");
        }

        usbConnection = usbManager.openDevice(usbDevice);
        usbConnection.claimInterface(usbInterface, true);
        sequence = 0;

        //Get the interfaces
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint usbEp = usbInterface.getEndpoint(i);


            if (usbEp.getDirection() == UsbConstants.USB_DIR_IN && usbEp.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                usbInterrupt = usbEp;
            }
            if (usbEp.getDirection() == UsbConstants.USB_DIR_OUT && usbEp.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                usbOut = usbEp;
            }
            if (usbEp.getDirection() == UsbConstants.USB_DIR_IN && usbEp.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                usbIn = usbEp;
            }
        }

        //check for pinPad
        CCIDDescriptor desc = CCIDDescriptor.Parse(usbConnection.getRawDescriptors()).get(usbInterface.getId());
        pinPad = desc.getPinSupports().contains(CCIDDescriptor.PINSupport.Verification);
        supportApdu = desc.getFeatures().contains(CCIDDescriptor.Feature.ShortAPDU) || desc.getFeatures().contains(CCIDDescriptor.Feature.ShortAndExtendedAPDU);
        autoInit = desc.getFeatures().contains(CCIDDescriptor.Feature.AutoParamConfigViaATR);

        //Listen for state changes
        setupStateListener();
    }

    private void setupStateListener() {
        if (usbInterrupt != null) {
            if (callback != null && stateRequester == null) {
                stateRequester = StateListenerPool.scheduleWithFixedDelay(new StateRequesterTask(), 100, 100, TimeUnit.MILLISECONDS);
            } else if (callback == null && stateRequester != null) {
                stateRequester.cancel(false);
                stateRequester = null;
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (stateRequester != null) {
            stateRequester.cancel(false);
            stateRequester = null;
        }
        if (usbInterface != null) {
            usbConnection.releaseInterface(usbInterface);
            usbConnection.close();
            usbConnection = null;
            usbInterface = null;
        }
    }

    private class StateRequesterTask implements Runnable {



        @Override
        public void run() {
            byte[] buffer = new byte[10];
            int count = usbConnection.bulkTransfer(usbInterrupt, buffer, buffer.length, 100);

            if (count < 0) return; //no info

            if (count < 2) {
                Log.w(TAG, "CCID interrupt read returned an invalid length: " + count);
                return;
            }

            if (buffer[0] == (byte) 0x51) {
                Log.w(TAG, String.format("CCID interrupt read returned an error : %x", buffer[3]));
            }

            if (buffer[0] != (byte) 0x50) {
                Log.w(TAG, String.format("CCID interrupt read returned an invalid type: %x", buffer[0]));
                return;
            }

            Log.i(TAG, String.format("CCID interrupt read returned the following status: %x", buffer[1]));
            if ((buffer[1] & (byte) 0x03) == (byte) 0x02)
                callback.removed();
            else if ((buffer[1] & (byte) 0x03) == (byte) 0x03)
                callback.inserted();
        }
    }

    public synchronized byte[] powerOn() throws IOException {
        return transmit((byte) 0x62/*IccPowerOn*/, null, (byte)0x80/*DataBlock*/, false).data;
    }

    public synchronized void powerOff() throws IOException {
        transmit((byte)0x63/*IccPowerOff*/, null, (byte)0x81/*SlotStatus*/, false);
    }

    public synchronized  void init() throws IOException {
        if (!autoInit) {
            //TODO:determine Fi/Di from ATR (for now we assume 0x13 for all eID)
            //See: 9.2 http://read.pudn.com/downloads132/doc/comm/563504/ISO-IEC%207816/ISO%2BIEC%207816-3-2006.pdf

            if (!supportApdu) {
                //TPDU for PPS
                transmit((byte) 0x6F, new byte[]{(byte) 0xFF, 0x10, 0x13, (byte) 0xFC}, (byte) 0x80, false);
            }

            //set params
            byte[] pds = new byte[5];
            pds[0] = 0x13; //bmFindexDindex: Fi/f(max) = 372/5, Di = 4
            pds[1] = 0x00; //bmTCCKST0: ignored (PCSC compatibility issue)
            pds[2] = 0x00; //bGuardTimeT0: default
            pds[3] = 0x0A; //bWaitingIntegerT0:  WI value
            pds[4] = 0x00; //bClockStop: no clock stop
            transmit((byte) 0x61/*SetParameters*/, pds, (byte) 0x82/*Parameters*/, true);
        }
    }

    public synchronized byte[] transmitApdu(byte[] apdu) throws IOException {
        return transmit((byte)0x6F /*XfrBlock*/, apdu,(byte)0x80/*DataBlock*/, true).data;
    }

    public synchronized byte[] transmitApduWithPin(byte[] apdu) throws IOException {
        //See CCID 1.10: 8.1.3 (PIN uses a BCD format conversion with PIN length insertion)
        //See USB_LANGID: http://www.usb.org/developers/docs/USB_LANGIDs.pdf
        byte[] pvds = new byte[15 + apdu.length];
        pvds[0] = 0x00; //bPINOperation: PIN Verification
        pvds[1] = 0x00; //bTimeOut: default
        pvds[2] = (byte)0x89; //bmFormatString: PIN Value Offset = byte 1, left justify, BCD format
        pvds[3] = (byte)0x47; //bmPINBlockString: PIN Len Size = 4 bits, Encode PIN Len = 7 bytes
        pvds[4] = (byte)0x04; //bmPINLengthFormat: PIN Len Offset = 4 bits
        pvds[5] = (byte)0x04; //wPINMaxExtraDigit: Min PIN Len = 4
        pvds[6] = (byte)0x04; //wPINMaxExtraDigit, cont.: Max PIN Len = 4
        pvds[7] = (byte)0x03; //bEntryValidationCondition: Validate on Max PIN Size | Validate Key Press
        pvds[8] = (byte)0xFF; //bNumberMessage: Number of messages = default
        pvds[9] = (byte)0x04; //wLangId: Country = US
        pvds[10] = (byte)0x09; //wLangId, cont.: Language = EN
        pvds[11] = (byte)0x00; //bMsgIndex: ignored number of messages is default
        pvds[12] = (byte)0x00; //bTeoPrologue: T=1 only
        pvds[13] = (byte)0x00; //bTeoPrologue, cont.: T=1 only
        pvds[14] = (byte)0x00; //bTeoPrologue, cont.: T=1 only
        System.arraycopy(apdu, 0, pvds, 15, apdu.length);

        return transmit((byte) 0x69 /*Secure*/, pvds, (byte)0x80, true).data;
    }

    public Response transmit(byte cmd, byte[] data, byte rtn, boolean waitIcc) throws IOException {
        sequence = (sequence + 1) % 0xFF;
        byte[] req = new byte[(data == null ? 0 : data.length) + 10];
        req[0] = cmd;
        req[1] = (byte) (req.length - 10); //(data) length
        req[2] = 0x00; //length, continued (we don't support long lenghts)
        req[3] = 0x00; //length, continued (we don't support long lenghts)
        req[4] = 0x00; //length, continued (we don't support long lenghts)
        req[5] = (byte) 0; //slot
        req[6] = (byte) sequence;
        req[7] = (byte)((cmd == (byte)0x6F) ? 0x01 : 0x00) ; //Not used (Xfr: Block Waiting Timeout)
        req[8] = 0x00; //Not used (Xfr: Param (short APDU))
        req[9] = 0x00; //Not used (Xfr: Param, continued (short APDU))
        if (data != null)
            System.arraycopy(data, 0, req, 10, data.length);

        int count;

        count = usbConnection.bulkTransfer(usbOut, req, req.length, 5000);
        if (count < 0) {
            throw new IOException("Failed to send data to the CCID reader");
        }
//        Log.v(TAG, String.format("Sent %d bytes to BULK-OUT: %s", count, Hex.toHexString(req, 0, count)));

        SlotStatus status;
        byte[] rsp = new byte[271];
        do {
            count = usbConnection.bulkTransfer(usbIn, rsp, rsp.length, 10000);
            if (count < 0) {
                throw new IOException("Failed to read data from the CCID reader");
            }
//            Log.v(TAG, String.format("Read %d bytes from BULK-IN: %s", count, Hex.toHexString(rsp, 0, count)));
            status = validateResponse(rsp, rtn);
        } while (waitIcc && status != SlotStatus.Active);

        Response retVal = new Response();
        retVal.param = rsp[9];
        if (count > 10) {
            retVal.data = new byte[count-10];
            System.arraycopy(rsp, 10, retVal.data, 0, count-10);
        } else {
            retVal.data = new byte[0];
        }
        return retVal;
    }


    private SlotStatus validateResponse(byte[] rsp, byte type) throws IOException {
        if (rsp.length < 10) {
            throw new IOException("The response is to short");
        }

        if (rsp[0] != type) {
            Log.w(TAG, String.format("Unexpected CCID reader response: %X (should be %X)", rsp[0], type ));
            throw new IOException(String.format("Illegal CCID reader response (wrong type: %X)", rsp[0]));
        }
        if (rsp[6] != (byte)sequence) {
            throw new IOException("Illegal CCID reader response (wrong sequence)");
        }

        if ((rsp[7] & (byte)0x03) == 0x01 && rsp[8] == 0x00) {
            // throw new UnsupportedOperationException("Command not supported by the reader");
            throw new IOException("Command not supported by the reader");
        }
        //No need to check errors like PIN_TIMEOUT (0xF0) and PIN_CANCELLED (0xEF), they aren't returned.
        if ((rsp[7] & (byte)0x03) != 0x00) {
            throw new CCIDException(String.format("Command Error returned by the CCID reader: %x", rsp[8]), rsp[7], rsp[8]);
        }

        switch((byte)(rsp[7] & ((byte) 0xC0))) {
            case (byte)0x80:
                return SlotStatus.Missing;
            case (byte)0x40:
                return SlotStatus.Inactive;
            case (byte)0x00:
                return SlotStatus.Active;
            default:
                throw new IOException(String.format("Invalid slot status received from the CCID reader: %X", (byte)(rsp[7] & ((byte) 0xC0))));
        }
    }
}