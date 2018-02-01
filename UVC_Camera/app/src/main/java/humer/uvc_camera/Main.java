package humer.uvc_camera;

        import android.app.Activity;
        import android.app.PendingIntent;
        import android.content.Intent;
        import android.graphics.Bitmap;
        import android.graphics.BitmapFactory;
        import android.graphics.ImageFormat;
        import android.graphics.Rect;
        import android.graphics.YuvImage;
        import android.hardware.usb.UsbConstants;
        import android.hardware.usb.UsbDevice;
        import android.hardware.usb.UsbDeviceConnection;
        import android.hardware.usb.UsbEndpoint;
        import android.hardware.usb.UsbInterface;
        import android.hardware.usb.UsbManager;
        import android.hardware.usb.UsbRequest;
        import android.os.Bundle;
        import android.os.Environment;
        import android.util.Log;
        import android.view.View;
        import android.widget.ImageView;
        import android.widget.Toast;

        import java.io.ByteArrayOutputStream;
        import java.io.File;
        import java.io.FileOutputStream;
        import java.io.IOException;
        import java.nio.ByteBuffer;
        import java.util.ArrayList;
        import java.util.Arrays;
        import java.util.HashMap;
        import java.util.concurrent.Callable;

        import humer.uvc_camera.UsbIso;

public class Main extends Activity {

    private static final String ACTION_USB_PERMISSION = "humer.uvc_camera.USB_PERMISSION";

    // USB codes:
// Request types (bmRequestType):
    private static final int RT_STANDARD_INTERFACE_SET = 0x01;
    private static final int RT_CLASS_INTERFACE_SET    = 0x21;
    private static final int RT_CLASS_INTERFACE_GET    = 0xA1;
    // Video interface subclass codes:
    private static final int SC_VIDEOCONTROL           = 0x01;
    private static final int SC_VIDEOSTREAMING         = 0x02;
    // Standard request codes:
    private static final int SET_INTERFACE             = 0x0b;
    // Video class-specific request codes:
    private static final int SET_CUR                   = 0x01;
    private static final int GET_CUR                   = 0x81;
    // VideoControl interface control selectors (CS):
    private static final int VC_REQUEST_ERROR_CODE_CONTROL = 0x02;
    // VideoStreaming interface control selectors (CS):
    private static final int VS_PROBE_CONTROL             = 0x01;
    private static final int VS_COMMIT_CONTROL            = 0x02;
    private static final int VS_STILL_PROBE_CONTROL       = 0x03;
    private static final int VS_STILL_COMMIT_CONTROL      = 0x04;
    private static final int VS_STREAM_ERROR_CODE_CONTROL = 0x06;
    private static final int VS_STILL_IMAGE_TRIGGER_CONTROL = 0x05;

    private enum CameraType { arkmicro, microdia, logitechC310, econ_5MP_USB2, econ_5MP_USB3, econ_8MP_USB3, delock, wellta };

    private UsbManager            usbManager;
    private CameraType            cameraType;
    private UsbDevice             camDevice;
    private UsbDeviceConnection   camDeviceConnection;
    private UsbInterface          camControlInterface;
    private UsbInterface          camStreamingInterface;
    private int                   camStreamingAltSetting;
    private UsbEndpoint           camStreamingEndpoint;
    private boolean               bulkMode;
    private int                   camFormatIndex;
    private int                   camFrameIndex;
    private int                   camFrameInterval;
    private UsbIso                usbIso;
    private int                   packetsPerRequest;
    private int                   maxPacketSize;
    private int                   imageWidth;
    private int                   imageHeight;
    private int                   activeUrbs;
    private boolean               camIsOpen;
    private boolean               backgroundJobActive;

    private ImageView             imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_main);
        imageView = (ImageView)findViewById(R.id.imageView);
        usbManager = (UsbManager)getSystemService(USB_SERVICE);
        // log("Started 3");
    }

    @Override
    protected void onPause () {
        super.onPause();
        try {
            closeCameraDevice(); }
        catch (Exception e) {
            displayErrorMessage(e); }}

    public void findCamButtonClickEvent (View view) {
        try {
            findCam(); }
        catch (Exception e) {
            displayErrorMessage(e);
            return; }
        displayMessage("USB camera found: " + camDevice.getDeviceName());
        // listDeviceInterfaces(camDevice);
    }

    private void listDevice (UsbDevice usbDevice) {
        log("Interface count: " + usbDevice.getInterfaceCount());
        int interfaces = usbDevice.getInterfaceCount();
        for (int i = 0; i < interfaces; i++) {
            UsbInterface usbInterface = usbDevice.getInterface(i);
            log("-Interface " + i + ": id=" + usbInterface.getId() + " class=" + usbInterface.getInterfaceClass() + " subclass=" + usbInterface.getInterfaceSubclass() + " protocol=" + usbInterface.getInterfaceProtocol());
            // UsbInterface.getAlternateSetting() has been added in Android 5.
            int endpoints = usbInterface.getEndpointCount();
            for (int j = 0; j < endpoints; j++) {
                UsbEndpoint usbEndpoint = usbInterface.getEndpoint(j);
                log("- Endpoint " + j + ": addr=" + usbEndpoint.getAddress() + " [direction=" + usbEndpoint.getDirection() + " endpointNumber=" + usbEndpoint.getEndpointNumber() + "] " +
                        " attrs=" + usbEndpoint.getAttributes() + " interval=" + usbEndpoint.getInterval() + " maxPacketSize=" + usbEndpoint.getMaxPacketSize() + " type=" + usbEndpoint.getType()); }}}

    private void findCam() throws Exception {
        camDevice = findCameraDevice();
        if (camDevice == null) {
            throw new Exception("No USB camera device found."); }}

    private UsbDevice findCameraDevice() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        log("USB devices count = " + deviceList.size());
        for (UsbDevice usbDevice : deviceList.values()) {
            log("USB device \"" + usbDevice.getDeviceName() + "\": " + usbDevice);
            if (checkDeviceHasVideoStreamingInterface(usbDevice)) {
                return usbDevice; }}
        return null; }

    private boolean checkDeviceHasVideoStreamingInterface (UsbDevice usbDevice) {
        return getVideoStreamingInterface(usbDevice) != null; }

    private UsbInterface getVideoControlInterface (UsbDevice usbDevice) {
        return findInterface(usbDevice, UsbConstants.USB_CLASS_VIDEO, SC_VIDEOCONTROL, false); }

    private UsbInterface getVideoStreamingInterface (UsbDevice usbDevice) {
        return findInterface(usbDevice, UsbConstants.USB_CLASS_VIDEO, SC_VIDEOSTREAMING, true); }

    private UsbInterface findInterface (UsbDevice usbDevice, int interfaceClass, int interfaceSubclass, boolean withEndpoint) {
        int interfaces = usbDevice.getInterfaceCount();
        for (int i = 0; i < interfaces; i++) {
            UsbInterface usbInterface = usbDevice.getInterface(i);
            if (usbInterface.getInterfaceClass() == interfaceClass && usbInterface.getInterfaceSubclass() == interfaceSubclass && (!withEndpoint || usbInterface.getEndpointCount() > 0)) {
                return usbInterface; }}
        return null; }

    public void requestPermissionButtonClickEvent (View view) {
        try {
            findCam(); }
        catch (Exception e) {
            displayErrorMessage(e);
            return; }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        // IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        // registerReceiver(mUsbReceiver, filter);
        usbManager.requestPermission(camDevice, permissionIntent); }

    public void listDeviceButtonClickEvent (View view) {
        try {
            findCam();
            listDevice(camDevice); }
        catch (Exception e) {
            displayErrorMessage(e);
            return; }}

    public void openCamButtonClickEvent (View view) {
        try {
            closeCameraDevice(); }
        catch (Exception e) {
            displayErrorMessage(e);
            return; }
        try {
            openCam(); }
        catch (Exception e) {
            displayErrorMessage(e);
            return; }
        // log("streamingInterfaceId=" + camStreamingInterface.getId() + " streamingEndpointAddr=0x" + Integer.toHexString(camStreamingEndpoint.getAddress()));
        displayMessage("OK"); }

    private void openCam() throws Exception {
        findCam();
        openCameraDevice();
        initCamera();
        camIsOpen = true; }

    private void openCameraDevice() throws Exception {
        if (!usbManager.hasPermission(camDevice)) {
            throw new Exception("Permission missing for camera device.");
        }
        cameraType = detectCameraType(camDevice);
        if (cameraType == null) {
            throw new Exception("Camera type not recognized.");
        }
        // (For transfer buffer sizes > 196608 the kernel file drivers/usb/core/devio.c must be patched.)
        switch (cameraType) {                           // temporary solution
            case arkmicro:
                camStreamingAltSetting = 11;              // 11 = 3x1000 bytes packet size
                maxPacketSize = 3 * 1000;
                camFormatIndex = 1;                       // bFormatIndex: 1 = uncompressed YUY2, 2 = MJPEG
                camFrameIndex = 1;                        // bFrameIndex: 1 = 640 x 480, 2 = 160 x 120, 3 = 176 x 144, 4 = 320 x 240, 5 = 352 x 288
                // imageWidth = 640;
                // imageHeight = 480;
                camFrameInterval = 2000000;               //
                // camFrameInterval = 333333;             // 333333 = 30 fps, 2000000 = 5 fps
                packetsPerRequest = 1;
                activeUrbs = 4;
                break;
            case microdia:
                camStreamingAltSetting = 6;              // 6 = 3x1024 bytes packet size
                maxPacketSize = 3 * 1024;
                camFormatIndex = 2;                       // bFormatIndex: 1 = uncompressed YUY2, 2 = MJPEG
                camFrameIndex = 1;                        // bFrameIndex: 1 = 1280 x 720,
                // imageWidth = 1280;
                // imageHeight = 720;
                camFrameInterval = 666666;
                // camFrameInterval = 666666;             // 666666 = 15 fps
                packetsPerRequest = 1;
                activeUrbs = 4;
                break;
            case logitechC310:
                camStreamingAltSetting = 11;              // 11 = 3x1020 bytes packet size
                maxPacketSize = 3 * 1020;
                camFormatIndex = 2;                       // bFormatIndex: 1 = uncompressed YUY2, 2 = MJPEG
                camFrameIndex = 1;                        // bFrameIndex: 1 = 640 x 480, 2 = 160 x 120, 5 = 320 x 240, 12 = 800 x 600, 19 = 1280 x 960
                // imageWidth = 160;
                // imageHeight = 120;
                camFrameInterval = 2000000;               // dwFrameInterval: 5 Hz
                // camFrameInterval = 333333;
                activeUrbs = 16;
                break;
            case econ_5MP_USB2:
                camStreamingAltSetting = 7;               // 7=3x 1024 bytes packet size
                maxPacketSize = 3 * 1024;
                camFormatIndex = 2;                       // bFormatIndex: 1 = uncompressed YUY2, 2 = MJPEG
                camFrameIndex = 1;                        // bFrameIndex: 1 = 640 x 480, 3 = 320 x 240
                // imageWidth = 320;
                // imageHeight = 240;
                // camFrameInterval = 333333;                // dwFrameInterval: 30 Hz
                camFrameInterval = 2000000;               // dwFrameInterval: 5 Hz
                packetsPerRequest = 8;
                activeUrbs = 4;
                break;
            case econ_5MP_USB3:
                camStreamingAltSetting = 0;               // Bulk endpoint
                maxPacketSize = 3068;
                // maxPacketSize = 4096;
                camFormatIndex = 2;                       // bFormatIndex: 1 = uncompressed YUY2, 2=MJPEG
                camFrameIndex = 3;                        // bFrameIndex: 1 = 640 x 480, 2 = 1920 x 1080, 3 = 1280 x 720, 4 = 2592 x 1944
                // imageWidth = 640;
                // imageHeight = 480;
                activeUrbs = 8;
                break;
            case econ_8MP_USB3:
                camStreamingAltSetting = 1;               //  3x 1024 bytes packet size
                // maxPacketSize = 2924;
                maxPacketSize = 3 * 1024;
                camFormatIndex = 1;                       // bFormatIndex: 1 = uncompressed YUY2
                camFrameIndex = 1;                        // bFrameIndex: 1 = 640 x 480
                // imageWidth = 640;
                // imageHeight = 480;
                camFrameInterval = 333333;                // dwFrameInterval: 30 Hz
                packetsPerRequest = 8;
                activeUrbs = 32;
                break;
            case delock:
                camStreamingAltSetting = 3;               // 7 = 3x1024 bytes packet size, 6 = 3x896 = 2'688, 3 = 1024
                maxPacketSize = 1024;
                camFormatIndex = 1;                       // bFormatIndex: 1 = MJPEG, 2 = Uncompressed YUY2
                camFrameIndex = 2;                        // bFrameIndex
                // imageWidth = 160;
                // imageHeight = 120;
                camFrameInterval = 2000000;               // dwFrameInterval: 5 Hz
                packetsPerRequest = 128;
                activeUrbs = 4;
                break;
            case wellta:
                camStreamingAltSetting = 1;               // 1 = 3x1024 bytes packet size, 3 = 1024, 4 = 512
                maxPacketSize = 3 * 1024;
                camFormatIndex = 1;                       // bFormatIndex: 1 = MJPEG, 2 = Uncompressed YUY2
                camFrameIndex = 4;                        // bFrameIndex: 4=640x480
                camFrameInterval = 2000000;
                // camFrameInterval = 333333;                // dwFrameInterval: 30 Hz
                packetsPerRequest = 128;
                activeUrbs = 16;
                break;
            default:
                throw new AssertionError();
            }
            camControlInterface = getVideoControlInterface(camDevice);
            camStreamingInterface = getVideoStreamingInterface(camDevice);
            if (camStreamingInterface.getEndpointCount() < 1) {
                throw new Exception("Streaming interface has no endpoint.");
            }
            camStreamingEndpoint = camStreamingInterface.getEndpoint(0);
            bulkMode = camStreamingEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK;
            camDeviceConnection = usbManager.openDevice(camDevice);
            if (camDeviceConnection == null) {
                throw new Exception("Unable to open camera device connection.");
            }
            if (!camDeviceConnection.claimInterface(camControlInterface, true)) {
                throw new Exception("Unable to claim camera control interface.");
            }
            if (!camDeviceConnection.claimInterface(camStreamingInterface, true)) {
                throw new Exception("Unable to claim camera streaming interface.");
            }
            usbIso = new UsbIso(camDeviceConnection.getFileDescriptor(), packetsPerRequest, maxPacketSize);
            usbIso.preallocateRequests(activeUrbs);
        }


    private CameraType detectCameraType (UsbDevice dev) {
        if (dev.getVendorId() == 0x046D && dev.getProductId() == 0x081B) {
            return CameraType.logitechC310; }
        else if (dev.getVendorId() == 0x0c45 && dev.getProductId() == 0x6366) {
            return CameraType.microdia; }
        else if (dev.getVendorId() == 0x18ec && dev.getProductId() == 0x3390) {
            return CameraType.arkmicro; }
        else if (dev.getVendorId() == 0x2560 && dev.getProductId() == 0xC151) {
            return CameraType.econ_5MP_USB3; }
        else if (dev.getVendorId() == 0x2560 && dev.getProductId() == 0xC080) {
            return CameraType.econ_8MP_USB3; }
        else if (dev.getVendorId() == 0x0AC8 && dev.getProductId() == 0x3470) {
            return CameraType.delock; }
        else if (dev.getVendorId() == 0x058f && dev.getProductId() == 0x3822) {
            return CameraType.wellta; }
        else {
            return null; }}

    private void closeCameraDevice() throws IOException {
        if (usbIso != null) {
            usbIso.dispose();
            usbIso = null; }
        if (camDeviceConnection != null) {
            camDeviceConnection.releaseInterface(camControlInterface);
            camDeviceConnection.releaseInterface(camStreamingInterface);
            camDeviceConnection.close();
            camDeviceConnection = null; }}

    private void initCamera() throws Exception {
        try {
            getVideoControlErrorCode(); }                // to reset previous error states
        catch (Exception e) {
            log("Warning: getVideoControlErrorCode() failed: " + e); }   // ignore error, some cameras do not support the request
        enableStreaming(false);
        try {
            getVideoStreamErrorCode(); }                // to reset previous error states
        catch (Exception e) {
            log("Warning: getVideoStreamErrorCode() failed: " + e); }   // ignore error, some cameras do not support the request
        initStreamingParms();
        if (cameraType == CameraType.econ_5MP_USB2) {
            initStillImageParms(); }
        //...
    }

    public void test1ButtonClickEvent (View view) {
        try {
            if (!camIsOpen) {
                openCam(); }
            startBackgroundJob(new Callable<Void>() {
                @Override
                public Void call () throws Exception {
                    if (bulkMode) {
                        testBulkRead1();
                        // testBulkRead2();
                        // testBulkRead3();
                        // testBulkRead4();
                    }
                    else {
                        testIsochronousRead1();
                    }
                    return null;
                }
            }); }
        catch (Exception e) {
            displayErrorMessage(e);
            return; }
        displayMessage("OK"); }

    public void test2ButtonClickEvent (View view) {
        try {
            if (!camIsOpen) {
                openCam(); }
            startBackgroundJob(new Callable<Void>() {
                @Override
                public Void call () throws Exception {
                    if (bulkMode) {
                        // testBulkRead1();
                        // testBulkRead2();
                        // testBulkRead3();
                        testBulkRead4();
                    }
                    else {
                        testIsochronousRead2(); }
                    return null;
                }
            }); }
        catch (Exception e) {
            displayErrorMessage(e);
            return; }
        displayMessage("OK"); }

    private void testBulkRead1() throws Exception {
        ArrayList<String> logArray = new ArrayList<String>(512);
        // log("maxPacketSize=" + camStreamingEndpoint.getMaxPacketSize());
        // UsbRequest usbRequest = new UsbRequest();
        // usbRequest.initialize(camDeviceConnection, camStreamingEndpoint);
        //
        // final int maxBulkTransferSize = 16384;          // hard-coded limit in devio.c
        enableStreaming(true);
        // log("enable streaming passed");
        // log("Stream error code: " + getVideoStreamErrorCode());
        // byte[] buf = new byte[maxBulkTransferSize];
        // byte[] buf = new byte[camStreamingEndpoint.getMaxPacketSize()];
        byte[] buf = new byte[maxPacketSize];
        for (int i = 0; i < 1000; i++) {
            int len = camDeviceConnection.bulkTransfer(camStreamingEndpoint, buf, buf.length, 100);
            StringBuilder logEntry = new StringBuilder();
            logEntry.append("len=" + len + ((len <= 0) ? "" : " " + hexDump(buf, Math.min(32, len))));
            logArray.add(logEntry.toString()); }
        // log("Stream error code: " + getVideoStreamErrorCode());
        enableStreaming(false);
        for (String s : logArray) {
            log(s); }}

    private void testBulkRead2() throws Exception {
        enableStreaming(true);
        UsbRequest usbRequest = new UsbRequest();
        if (!usbRequest.initialize(camDeviceConnection, camStreamingEndpoint)) {
            // usbhost.c usb_request_new() checks that endpoint type is bulk or int.
            throw new Exception("UsbRequest.initialize() failed."); }
        // ByteBuffer buf = ByteBuffer.allocate(0x4000);
        // ByteBuffer buf = ByteBuffer.allocate(0x1000);
        ByteBuffer buf = ByteBuffer.allocate(maxPacketSize);
        if (!usbRequest.queue(buf, buf.capacity())) {
            throw new Exception("UsbRequest.queue() failed."); }
        UsbRequest req2 = camDeviceConnection.requestWait();
        if (req2 == null) {
            throw new Exception("UsbDeviceConnection.requestWait() failed."); }
        if (req2 != usbRequest) {
            throw new Exception("UsbDeviceConnection.requestWait() returned different request."); }
        log("buf.position=" + buf.position() + " limit=" + buf.limit() + " Data=" + hexDump(buf.array(), 32));
        usbRequest.close();
        enableStreaming(false); }

    private void testBulkRead3() throws Exception {
        enableStreaming(true);
        ArrayList<String> logArray = new ArrayList<String>(512);
        UsbRequest usbRequests[] = new UsbRequest[activeUrbs];
        for (int i = 0; i < activeUrbs; i++) {
            UsbRequest usbRequest = new UsbRequest();
            usbRequests[i] = usbRequest;
            if (!usbRequest.initialize(camDeviceConnection, camStreamingEndpoint)) {
                throw new Exception("UsbRequest.initialize() failed."); }
            ByteBuffer buf = ByteBuffer.allocate(maxPacketSize);
            usbRequest.setClientData(buf);
            if (!usbRequest.queue(buf, buf.capacity())) {
                throw new Exception("UsbRequest.queue() failed."); }}
        for (int i = 0; i < 200; i++) {
            UsbRequest usbRequest = camDeviceConnection.requestWait();
            if (usbRequest == null) {
                throw new Exception("UsbDeviceConnection.requestWait() failed."); }
            ByteBuffer buf = (ByteBuffer)usbRequest.getClientData();
            StringBuilder logEntry = new StringBuilder();
            logEntry.append("buf.position=" + buf.position() + " limit=" + buf.limit() + " Data=" + hexDump(buf.array(), 32));
            // log("buf.position=" + buf.position() + " limit=" + buf.limit() + " Data=" + hexDump(buf.array(), 32));
            logArray.add(logEntry.toString());
            buf.clear();
            if (!usbRequest.queue(buf, buf.capacity())) {
                throw new Exception("UsbRequest.queue() failed."); }}
        for (int i = 0; i < activeUrbs; i++) {
            usbRequests[i].cancel();
            usbRequests[i].close(); }
        enableStreaming(false);
        for (String s : logArray) {
            log(s); }}

    private void testBulkRead4() throws Exception {
        ArrayList<String> logArray = new ArrayList<String>(512);
        enableStreaming(true);
        ByteArrayOutputStream frameData = new ByteArrayOutputStream(0x20000);
        byte[] buf = new byte[maxPacketSize];
        boolean scanningForStart = true;
        int packetCount = 0;
        while (true) {
            int len = camDeviceConnection.bulkTransfer(camStreamingEndpoint, buf, buf.length, 250);
            StringBuilder logEntry = new StringBuilder();
            logEntry.append("len=" + len + ((len <= 0) ? "" : " data=" + hexDump(buf, Math.min(32, len))));
            logArray.add(logEntry.toString());
            boolean validPacket = len > 12 && buf[0] == 12 && buf[1] != 0 && buf[2] == 0 && buf[3] == 0 && buf[4] == 0 && buf[5] == 0;   // the 0 bytes are tested to skip garbage at the start of the transmission
            boolean lastPacketInFrame = validPacket && (buf[1] & 2) != 0;
            if (scanningForStart) {
                scanningForStart = !lastPacketInFrame; }
//     else if (len == -1) {
//       /* ignore ??? */ }
            else {
                if (!validPacket) {
                    for (String s : logArray) {log(s); }
                    throw new Exception("Invalid packet within frame."); }
                frameData.write(buf, 12, len - 12);
                if (lastPacketInFrame) {
                    break; }}
            if (++packetCount >= 2000) {
                for (String s : logArray) {log(s); }
                throw new Exception("No video frame received after " + packetCount + " packets."); }}
        enableStreaming(false);
        processReceivedMJpegVideoFrame(frameData.toByteArray());
        // saveReceivedVideoFrame(frameData.toByteArray());
        log("OK, packetCount=" + packetCount); }

    private void testIsochronousRead1() throws Exception {
        //Thread.sleep(500);
        ArrayList<String> logArray = new ArrayList<String>(512);
        int packetCnt = 0;
        int packet0Cnt = 0;
        int packet12Cnt = 0;
        int packetDataCnt = 0;
        int packetHdr8Ccnt = 0;
        int packetErrorCnt = 0;
        int frameCnt = 0;
        long time0 = System.currentTimeMillis();
        int frameLen = 0;
        int requestCnt = 0;
        byte[] data = new byte[maxPacketSize];
        enableStreaming(true);
        submitActiveUrbs();
        while (System.currentTimeMillis() - time0 < 10000) {
            // Thread.sleep(0, 1);               // ??????????
            boolean stopReq = false;
            UsbIso.Request req = usbIso.reapRequest(true);
            for (int packetNo = 0; packetNo < req.getPacketCount(); packetNo++) {
                packetCnt++;
                int packetLen = req.getPacketActualLength(packetNo);
                if (packetLen == 0) {
                    packet0Cnt++; }
                if (packetLen == 12) {
                    packet12Cnt++; }
                if (packetLen == 0) {
                    continue; }
                StringBuilder logEntry = new StringBuilder(requestCnt + "/" + packetNo + " len=" + packetLen);
                int packetStatus = req.getPacketStatus(packetNo);
                if (packetStatus != 0) {
                    log("Packet status=" + packetStatus);
                    stopReq = true;
                    break; }
                if (packetLen > 0) {
                    if (packetLen > maxPacketSize) {
                        throw new Exception("packetLen > maxPacketSize"); }
                    req.getPacketData(packetNo, data, packetLen);
                    logEntry.append(" data=" + hexDump(data, Math.min(32, packetLen)));
                    int headerLen = data[0] & 0xff;
                    if (headerLen < 2 || headerLen > packetLen) {
                        throw new IOException("Invalid payload header length. headerLen=" + headerLen + " packetLen=" + packetLen); }
                    int headerFlags = data[1] & 0xff;
                    if (headerFlags == 0x8c) {
                        packetHdr8Ccnt++; }
                    // logEntry.append(" hdrLen=" + headerLen + " hdr[1]=0x" + Integer.toHexString(headerFlags));
                    int dataLen = packetLen - headerLen;
                    if (dataLen > 0) {
                        packetDataCnt++; }
                    frameLen += dataLen;
                    if ((headerFlags & 0x40) != 0) {
                        logEntry.append(" *** Error ***");
                        packetErrorCnt++; }
                    if ((headerFlags & 2) != 0) {
                        logEntry.append(" EOF frameLen=" + frameLen);
                        frameCnt++;
                        // if (frameCnt == 2) {
                        //    sendStillImageTrigger(); }           // test ***********
                        frameLen = 0; }}
                // if (packetLen == 0 && frameLen > 0) {
                //    logEntry.append(" assumed EOF, framelen=" + frameLen);
                //    frameLen = 0; }
                // int streamErrorCode = getVideoStreamErrorCode();
                // if (streamErrorCode != 0) {
                //    logEntry.append(" streamErrorCode=" + streamErrorCode); }
                // int controlErrorCode = getVideoControlErrorCode();
                // if (controlErrorCode != 0) {
                //   logEntry.append(" controlErrorCode=" + controlErrorCode); }
                logArray.add(logEntry.toString()); }
            if (stopReq) {
                break; }
            requestCnt++;
            req.initialize(camStreamingEndpoint.getAddress());
            req.submit(); }
        try {
            enableStreaming(false); }
        catch (Exception e) {
            log("Exception during enableStreaming(false): " + e); }
        log("requests=" + requestCnt + " packetCnt=" + packetCnt + " packetErrorCnt=" + packetErrorCnt + " packet0Cnt=" + packet0Cnt + ", packet12Cnt=" + packet12Cnt + ", packetDataCnt=" + packetDataCnt + " packetHdr8cCnt=" + packetHdr8Ccnt + " frameCnt=" + frameCnt);
        for (String s : logArray) {
            log(s); }
    }

    private void testIsochronousRead2() throws Exception {
        // sendStillImageTrigger();            // test ***********
        // Thread.sleep(500);
        ArrayList<String> logArray = new ArrayList<String>(512);
        ByteArrayOutputStream frameData = new ByteArrayOutputStream(0x20000);
        long startTime = System.currentTimeMillis();
        int skipFrames = 0;
        // if (cameraType == CameraType.wellta) {
        //    skipFrames = 1; }                                // first frame may look intact but it is not always intact
        boolean frameComplete = false;
        byte[] data = new byte[maxPacketSize];
        enableStreaming(true);
        submitActiveUrbs();
        while (true) {
            // Thread.sleep(0, 100);               // ??????????
            if (System.currentTimeMillis() - startTime > 20000) {
                enableStreaming(false);
                for (String s : logArray) {
                    log(s); }
                throw new Exception("Timeout while waiting for image frame."); }
            UsbIso.Request req = usbIso.reapRequest(true);
            for (int packetNo = 0; packetNo < req.getPacketCount(); packetNo++) {
                int packetStatus = req.getPacketStatus(packetNo);
                if (packetStatus != 0) {
                    throw new IOException("Camera read error, packet status=" + packetStatus); }
                int packetLen = req.getPacketActualLength(packetNo);
                if (packetLen == 0) {
                    // if (packetLen == 0 && frameData.size() > 0) {         // assume end of frame
                    //   endOfFrame = true;
                    //   break; }
                    continue; }
                if (packetLen > maxPacketSize) {
                    throw new Exception("packetLen > maxPacketSize"); }
                req.getPacketData(packetNo, data, packetLen);
                int headerLen = data[0] & 0xff;
                if (headerLen < 2 || headerLen > packetLen) {
                    throw new IOException("Invalid payload header length."); }
                int headerFlags = data[1] & 0xff;
                int dataLen = packetLen - headerLen;
                boolean error = (headerFlags & 0x40) != 0;
                if (error && skipFrames == 0) {
                    // throw new IOException("Error flag set in payload header.");
                    log("Error flag detected, ignoring frame.");
                    skipFrames = 1; }
                boolean endOfFrame = (headerFlags & 2) != 0;
                if (dataLen > 0 && skipFrames == 0) {
                    frameData.write(data, headerLen, dataLen); }
                //
                // StringBuilder logEntry = new StringBuilder("packet " + packetNo + " len=" + packetLen);
                // if (dataLen > 0) logEntry.append(" data=" + hexDump(data, Math.min(32, packetLen)));
                // if (endOfFrame) logEntry.append(" EOF");
                // if (error) logEntry.append(" **Error**");
                // logArray.add(logEntry.toString());
                // final int frameDataSize = imageWidth * imageHeight * 2;
                // if (frameData.size() >= frameDataSize) {
                //    endOfFrame = true; }    // temp test
                if (endOfFrame) {
                    if (skipFrames > 0) {
                        log("Skipping frame, len= " + frameData.size());
                        frameData.reset();
                        skipFrames--; }
                    else {
                        frameComplete = true;
                        break; }}}
            if (frameComplete) {
                break; }
            req.initialize(camStreamingEndpoint.getAddress());
            req.submit(); }
        enableStreaming(false);
        log("frame data len = " + frameData.size());
        processReceivedMJpegVideoFrame(frameData.toByteArray());
        // saveReceivedVideoFrame(frameData.toByteArray());
        log("OK"); }

    private void submitActiveUrbs() throws IOException {
        long time0 = System.currentTimeMillis();
        for (int i = 0; i < activeUrbs; i++) {
            UsbIso.Request req = usbIso.getRequest();
            req.initialize(camStreamingEndpoint.getAddress());
            req.submit(); }
        // log("Time used for submitActiveUrbs: " + (System.currentTimeMillis() - time0) + "ms.");
    }

    private void saveReceivedVideoFrame (byte[] frameData) throws Exception {
        File file = new File(Environment.getExternalStorageDirectory(), "temp_usbcamtest1.bin");
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(file);
            fileOutputStream.write(frameData);
            fileOutputStream.flush(); }
        finally {
            fileOutputStream.close(); }}

    private void processReceivedVideoFrame1 (byte[] frameData) throws IOException {
        String fileName = new File(Environment.getExternalStorageDirectory(), "temp_usbcamtest1.frame").getPath();
        writeBytesToFile(fileName, frameData); }

    private void writeBytesToFile (String fileName, byte[] data) throws IOException {
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(fileName);
            fileOutputStream.write(data);
            fileOutputStream.flush(); }
        finally {
            fileOutputStream.close(); }}

    private void processReceivedVideoFrameYuv (byte[] frameData) throws IOException {
        log("before YuvImage");
        YuvImage yuvImage = new YuvImage(frameData, ImageFormat.YUY2, imageWidth, imageHeight, null);
        log("after YuvImage");
        File file = new File(Environment.getExternalStorageDirectory(), "temp_usbcamtest1.jpg");
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(file);
            Rect rect = new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight());
            log("before compressToJpeg");
            yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, fileOutputStream);
            log("after compressToJpeg");
            fileOutputStream.flush(); }
        finally {
            fileOutputStream.close(); }}

    private void processReceivedMJpegVideoFrame (byte[] mjpegFrameData) throws Exception {
        byte[] jpegFrameData = convertMjpegFrameToJpeg(mjpegFrameData);
        String fileName = new File(Environment.getExternalStorageDirectory(), "temp_usbcamtest1.jpg").getPath();
        writeBytesToFile(fileName, jpegFrameData);
        final Bitmap bitmap = BitmapFactory.decodeByteArray(jpegFrameData, 0, jpegFrameData.length);
        runOnUiThread(new Runnable() {
            @Override public void run () {
                imageView.setImageBitmap(bitmap); }}); }

    // see USB video class standard, USB_Video_Payload_MJPEG_1.5.pdf
    private byte[] convertMjpegFrameToJpeg (byte[] frameData) throws Exception {
        int frameLen = frameData.length;
        while (frameLen > 0 && frameData[frameLen - 1] == 0) {
            frameLen--; }
        if (frameLen < 100 || (frameData[0] & 0xff) != 0xff || (frameData[1] & 0xff) != 0xD8 || (frameData[frameLen-2] & 0xff) != 0xff || (frameData[frameLen-1] & 0xff) != 0xd9) {
            throw new Exception("Invalid MJPEG frame structure, length=" + frameData.length); }
        boolean hasHuffmanTable = findJpegSegment(frameData, frameLen, 0xC4) != -1;
        if (hasHuffmanTable) {
            if (frameData.length == frameLen) {
                return frameData; }
            return Arrays.copyOf(frameData, frameLen); }
        else {
            int segmentDaPos = findJpegSegment(frameData, frameLen, 0xDA);
            if (segmentDaPos == -1) {
                throw new Exception("Segment 0xDA not found in MJPEG frame data."); }
            byte[] a = new byte[frameLen + mjpgHuffmanTable.length];
            System.arraycopy(frameData, 0, a, 0, segmentDaPos);
            System.arraycopy(mjpgHuffmanTable, 0, a, segmentDaPos, mjpgHuffmanTable.length);
            System.arraycopy(frameData, segmentDaPos, a, segmentDaPos + mjpgHuffmanTable.length, frameLen - segmentDaPos);
            return a; }}

    private int findJpegSegment (byte[] a, int dataLen, int segmentType) {
        int p = 2;
        while (p <= dataLen - 6) {
            if ((a[p] & 0xff) != 0xff) {
                log("Unexpected JPEG data structure (marker expected).");
                break; }
            int markerCode = a[p + 1] & 0xff;
            if (markerCode == segmentType) {
                return p; }
            if (markerCode >= 0xD0 && markerCode <= 0xDA) {       // stop when scan data begins
                break; }
            int len = ((a[p + 2] & 0xff) << 8) + (a[p + 3] & 0xff);
            p += len + 2; }
        return -1; }

    private void initStreamingParms() throws Exception {
        final int timeout = 5000;
        int usedStreamingParmsLen;
        int len;
        byte[] streamingParms = new byte[26];
        // The e-com module produces errors with 48 bytes (UVC 1.5) instead of 26 bytes (UVC 1.1) streaming parameters! We could use the USB version info to determine the size of the streaming parameters.
        streamingParms[2] = (byte)camFormatIndex;                // bFormatIndex
        streamingParms[3] = (byte)camFrameIndex;                 // bFrameIndex
        packUsbInt(camFrameInterval, streamingParms, 4);         // dwFrameInterval
        log("Initial streaming parms: " + dumpStreamingParms(streamingParms));
        len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_SET, SET_CUR, VS_PROBE_CONTROL << 8, camStreamingInterface.getId(), streamingParms, streamingParms.length, timeout);
        if (len != streamingParms.length) {
            throw new Exception("Camera initialization failed. Streaming parms probe set failed, len=" + len + "."); }
        // for (int i = 0; i < streamingParms.length; i++) streamingParms[i] = 99;          // temp test
        len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_GET, GET_CUR, VS_PROBE_CONTROL << 8, camStreamingInterface.getId(), streamingParms, streamingParms.length, timeout);
        if (len != streamingParms.length) {
            throw new Exception("Camera initialization failed. Streaming parms probe get failed."); }
        log("Probed streaming parms: " + dumpStreamingParms(streamingParms));
        usedStreamingParmsLen = len;
        // log("Streaming parms length: " + usedStreamingParmsLen);
        len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_SET, SET_CUR, VS_COMMIT_CONTROL << 8, camStreamingInterface.getId(), streamingParms, usedStreamingParmsLen, timeout);
        if (len != streamingParms.length) {
            throw new Exception("Camera initialization failed. Streaming parms commit set failed."); }
        // for (int i = 0; i < streamingParms.length; i++) streamingParms[i] = 99;          // temp test
        len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_GET, GET_CUR, VS_COMMIT_CONTROL << 8, camStreamingInterface.getId(), streamingParms, usedStreamingParmsLen, timeout);
        if (len != streamingParms.length) {
            throw new Exception("Camera initialization failed. Streaming parms commit get failed."); }
        log("Final streaming parms: " + dumpStreamingParms(streamingParms)); }

    private String dumpStreamingParms (byte[] p) {
        StringBuilder s = new StringBuilder(128);
        s.append("hint=0x" + Integer.toHexString(unpackUsbUInt2(p, 0)));
        s.append(" format=" + (p[2] & 0xf));
        s.append(" frame=" + (p[3] & 0xf));
        s.append(" frameInterval=" + unpackUsbInt(p, 4));
        s.append(" keyFrameRate=" + unpackUsbUInt2(p, 8));
        s.append(" pFrameRate=" + unpackUsbUInt2(p, 10));
        s.append(" compQuality=" + unpackUsbUInt2(p, 12));
        s.append(" compWindowSize=" + unpackUsbUInt2(p, 14));
        s.append(" delay=" + unpackUsbUInt2(p, 16));
        s.append(" maxVideoFrameSize=" + unpackUsbInt(p, 18));
        s.append(" maxPayloadTransferSize=" + unpackUsbInt(p, 22));
        return s.toString(); }

    private void initStillImageParms() throws Exception {
        final int timeout = 5000;
        int len;
        byte[] parms = new byte[11];
        parms[0] = (byte) camFormatIndex;
        parms[1] = (byte) camFrameIndex;
        parms[2] = 1;
//   len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_GET, SET_CUR, VS_STILL_PROBE_CONTROL << 8, camStreamingInterface.getId(), parms, parms.length, timeout);
//   if (len != parms.length) {
//      throw new Exception("Camera initialization failed. Still image parms probe set failed. len=" + len); }
        len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_GET, GET_CUR, VS_STILL_PROBE_CONTROL << 8, camStreamingInterface.getId(), parms, parms.length, timeout);
        if (len != parms.length) {
            throw new Exception("Camera initialization failed. Still image parms probe get failed."); }
        log("Probed still image parms: " + dumpStillImageParms(parms));
        len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_SET, SET_CUR, VS_STILL_COMMIT_CONTROL << 8, camStreamingInterface.getId(), parms, parms.length, timeout);
        if (len != parms.length) {
            throw new Exception("Camera initialization failed. Still image parms commit set failed."); }
//   len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_SET, GET_CUR, VS_STILL_COMMIT_CONTROL << 8, camStreamingInterface.getId(), parms, parms.length, timeout);
//   if (len != parms.length) {
//      throw new Exception("Camera initialization failed. Still image parms commit get failed. len=" + len); }
//   log("Final still image parms: " + dumpStillImageParms(parms)); }
    }

    private String dumpStillImageParms (byte[] p) {
        StringBuilder s = new StringBuilder(128);
        s.append("bFormatIndex=" + (p[0] & 0xff));
        s.append(" bFrameIndex=" + (p[1] & 0xff));
        s.append(" bCompressionIndex=" + (p[2] & 0xff));
        s.append(" maxVideoFrameSize=" + unpackUsbInt(p, 3));
        s.append(" maxPayloadTransferSize=" + unpackUsbInt(p, 7));
        return s.toString(); }

    private static int unpackUsbInt (byte[] buf, int pos) {
        return unpackInt(buf, pos, false); }

    private static int unpackUsbUInt2 (byte[] buf, int pos) {
        return ((buf[pos + 1] & 0xFF) << 8) | (buf[pos] & 0xFF); }

    private static void packUsbInt (int i, byte[] buf, int pos) {
        packInt(i, buf, pos, false); }

    private static void packInt (int i, byte[] buf, int pos, boolean bigEndian) {
        if (bigEndian) {
            buf[pos]     = (byte)((i >>> 24) & 0xFF);
            buf[pos + 1] = (byte)((i >>> 16) & 0xFF);
            buf[pos + 2] = (byte)((i >>>  8) & 0xFF);
            buf[pos + 3] = (byte)(i & 0xFF); }
        else {
            buf[pos]     = (byte)(i & 0xFF);
            buf[pos + 1] = (byte)((i >>>  8) & 0xFF);
            buf[pos + 2] = (byte)((i >>> 16) & 0xFF);
            buf[pos + 3] = (byte)((i >>> 24) & 0xFF); }}

    private static int unpackInt (byte[] buf, int pos, boolean bigEndian) {
        if (bigEndian) {
            return (buf[pos] << 24) | ((buf[pos + 1] & 0xFF) << 16) | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF); }
        else {
            return (buf[pos + 3] << 24) | ((buf[pos + 2] & 0xFF) << 16) | ((buf[pos + 1] & 0xFF) << 8) | (buf[pos] & 0xFF); }}

    private void enableStreaming (boolean enabled) throws Exception {
        enableStreaming_usbFs(enabled); }

    private void enableStreaming_usbFs (boolean enabled) throws Exception {
        if (enabled && bulkMode) {
            // clearHalt(camStreamingEndpoint.getAddress());
        }
        int altSetting = enabled ? camStreamingAltSetting : 0;
        // For bulk endpoints, altSetting is always 0.
        usbIso.setInterface(camStreamingInterface.getId(), altSetting);
        if (!enabled) {
            usbIso.flushRequests();
            if (bulkMode) {
                // clearHalt(camStreamingEndpoint.getAddress());
            }}}

// public void clearHalt (int endpointAddr) throws IOException {
//    IntByReference ep = new IntByReference(endpointAddr);
//    int rc = libc.ioctl(fileDescriptor, USBDEVFS_CLEAR_HALT, ep.getPointer());
//    if (rc != 0) {
//       throw new IOException("ioctl(USBDEVFS_CLEAR_HALT) failed, rc=" + rc + "."); }}

    private void enableStreaming_direct (boolean enabled) throws Exception {
        if (!enabled) {
            return; }
        // Ist unklar, wie man das Streaming disabled. AltSetting muss 0 sein damit die Video-Daten kommen.
        int len = camDeviceConnection.controlTransfer(RT_STANDARD_INTERFACE_SET, SET_INTERFACE, 0, camStreamingInterface.getId(), null, 0, 1000);
        if (len != 0) {
            throw new Exception("SET_INTERFACE (direct) failed, len=" + len + "."); }}

    private void sendStillImageTrigger() throws Exception {
        byte buf[] = new byte[1];
        buf[0] = 1;
        int len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_SET, SET_CUR, VS_STILL_IMAGE_TRIGGER_CONTROL << 8, camStreamingInterface.getId(), buf, 1, 1000);
        if (len != 1) {
            throw new Exception("VS_STILL_IMAGE_TRIGGER_CONTROL failed, len=" + len + "."); }}

    // Resets the error code after retrieving it.
// Does not work with the e-con camera module!
    private int getVideoControlErrorCode() throws Exception {
        byte buf[] = new byte[1];
        buf[0] = 99;
        int len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_GET, GET_CUR, VC_REQUEST_ERROR_CODE_CONTROL << 8, 0, buf, 1, 1000);
        if (len != 1) {
            throw new Exception("VC_REQUEST_ERROR_CODE_CONTROL failed, len=" + len + "."); }
        return buf[0]; }

    // Does not work with Logitech C310? Always returns 0.
    private int getVideoStreamErrorCode() throws Exception {
        byte buf[] = new byte[1];
        buf[0] = 99;
        int len = camDeviceConnection.controlTransfer(RT_CLASS_INTERFACE_GET, GET_CUR, VS_STREAM_ERROR_CODE_CONTROL << 8, camStreamingInterface.getId(), buf, 1, 1000);
        if (len == 0) {
            return 0; }                   // ? (Logitech C310 returns len=0)
        if (len != 1) {
            throw new Exception("VS_STREAM_ERROR_CODE_CONTROL failed, len=" + len + "."); }
        return buf[0]; }

//------------------------------------------------------------------------------

    private static String hexDump (byte[] buf, int len) {
        StringBuilder s = new StringBuilder(len * 3);
        for (int p = 0; p < len; p++) {
            if (p > 0) {
                s.append(' '); }
            int v = buf[p] & 0xff;
            if (v < 16) {
                s.append('0'); }
            s.append(Integer.toHexString(v)); }
        return s.toString(); }

    private void displayMessage (final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run () {
                Toast.makeText(Main.this, msg, Toast.LENGTH_LONG).show(); }}); }

    private void log (String msg) {
        Log.i("UsbCamTest1", msg); }

    private void displayErrorMessage (Throwable e) {
        Log.e("UsbCamTest1", "Error in MainActivity", e);
        displayMessage("Error: " + e); }

    private void startBackgroundJob (final Callable callable) throws Exception {
        if (backgroundJobActive) {
            throw new Exception("Background job is already active."); }
        backgroundJobActive = true;
        Thread thread = new Thread() {
            @Override public void run() {
                try {
                    callable.call(); }
                catch (Throwable e) {
                    displayErrorMessage(e); }
                finally {
                    backgroundJobActive = false; }}};
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start(); }

    // see 10918-1:1994, K.3.3.1 Specification of typical tables for DC difference coding
    private static byte[] mjpgHuffmanTable = {
            (byte)0xff, (byte)0xc4, (byte)0x01, (byte)0xa2, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x05, (byte)0x01, (byte)0x01,
            (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x02, (byte)0x03, (byte)0x04, (byte)0x05, (byte)0x06, (byte)0x07, (byte)0x08,
            (byte)0x09, (byte)0x0a, (byte)0x0b, (byte)0x10, (byte)0x00, (byte)0x02, (byte)0x01, (byte)0x03, (byte)0x03, (byte)0x02,
            (byte)0x04, (byte)0x03, (byte)0x05, (byte)0x05, (byte)0x04, (byte)0x04, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x7d,
            (byte)0x01, (byte)0x02, (byte)0x03, (byte)0x00, (byte)0x04, (byte)0x11, (byte)0x05, (byte)0x12, (byte)0x21, (byte)0x31,
            (byte)0x41, (byte)0x06, (byte)0x13, (byte)0x51, (byte)0x61, (byte)0x07, (byte)0x22, (byte)0x71, (byte)0x14, (byte)0x32,
            (byte)0x81, (byte)0x91, (byte)0xa1, (byte)0x08, (byte)0x23, (byte)0x42, (byte)0xb1, (byte)0xc1, (byte)0x15, (byte)0x52,
            (byte)0xd1, (byte)0xf0, (byte)0x24, (byte)0x33, (byte)0x62, (byte)0x72, (byte)0x82, (byte)0x09, (byte)0x0a, (byte)0x16,
            (byte)0x17, (byte)0x18, (byte)0x19, (byte)0x1a, (byte)0x25, (byte)0x26, (byte)0x27, (byte)0x28, (byte)0x29, (byte)0x2a,
            (byte)0x34, (byte)0x35, (byte)0x36, (byte)0x37, (byte)0x38, (byte)0x39, (byte)0x3a, (byte)0x43, (byte)0x44, (byte)0x45,
            (byte)0x46, (byte)0x47, (byte)0x48, (byte)0x49, (byte)0x4a, (byte)0x53, (byte)0x54, (byte)0x55, (byte)0x56, (byte)0x57,
            (byte)0x58, (byte)0x59, (byte)0x5a, (byte)0x63, (byte)0x64, (byte)0x65, (byte)0x66, (byte)0x67, (byte)0x68, (byte)0x69,
            (byte)0x6a, (byte)0x73, (byte)0x74, (byte)0x75, (byte)0x76, (byte)0x77, (byte)0x78, (byte)0x79, (byte)0x7a, (byte)0x83,
            (byte)0x84, (byte)0x85, (byte)0x86, (byte)0x87, (byte)0x88, (byte)0x89, (byte)0x8a, (byte)0x92, (byte)0x93, (byte)0x94,
            (byte)0x95, (byte)0x96, (byte)0x97, (byte)0x98, (byte)0x99, (byte)0x9a, (byte)0xa2, (byte)0xa3, (byte)0xa4, (byte)0xa5,
            (byte)0xa6, (byte)0xa7, (byte)0xa8, (byte)0xa9, (byte)0xaa, (byte)0xb2, (byte)0xb3, (byte)0xb4, (byte)0xb5, (byte)0xb6,
            (byte)0xb7, (byte)0xb8, (byte)0xb9, (byte)0xba, (byte)0xc2, (byte)0xc3, (byte)0xc4, (byte)0xc5, (byte)0xc6, (byte)0xc7,
            (byte)0xc8, (byte)0xc9, (byte)0xca, (byte)0xd2, (byte)0xd3, (byte)0xd4, (byte)0xd5, (byte)0xd6, (byte)0xd7, (byte)0xd8,
            (byte)0xd9, (byte)0xda, (byte)0xe1, (byte)0xe2, (byte)0xe3, (byte)0xe4, (byte)0xe5, (byte)0xe6, (byte)0xe7, (byte)0xe8,
            (byte)0xe9, (byte)0xea, (byte)0xf1, (byte)0xf2, (byte)0xf3, (byte)0xf4, (byte)0xf5, (byte)0xf6, (byte)0xf7, (byte)0xf8,
            (byte)0xf9, (byte)0xfa, (byte)0x01, (byte)0x00, (byte)0x03, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01,
            (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x01, (byte)0x02, (byte)0x03, (byte)0x04, (byte)0x05, (byte)0x06, (byte)0x07, (byte)0x08, (byte)0x09, (byte)0x0a,
            (byte)0x0b, (byte)0x11, (byte)0x00, (byte)0x02, (byte)0x01, (byte)0x02, (byte)0x04, (byte)0x04, (byte)0x03, (byte)0x04,
            (byte)0x07, (byte)0x05, (byte)0x04, (byte)0x04, (byte)0x00, (byte)0x01, (byte)0x02, (byte)0x77, (byte)0x00, (byte)0x01,
            (byte)0x02, (byte)0x03, (byte)0x11, (byte)0x04, (byte)0x05, (byte)0x21, (byte)0x31, (byte)0x06, (byte)0x12, (byte)0x41,
            (byte)0x51, (byte)0x07, (byte)0x61, (byte)0x71, (byte)0x13, (byte)0x22, (byte)0x32, (byte)0x81, (byte)0x08, (byte)0x14,
            (byte)0x42, (byte)0x91, (byte)0xa1, (byte)0xb1, (byte)0xc1, (byte)0x09, (byte)0x23, (byte)0x33, (byte)0x52, (byte)0xf0,
            (byte)0x15, (byte)0x62, (byte)0x72, (byte)0xd1, (byte)0x0a, (byte)0x16, (byte)0x24, (byte)0x34, (byte)0xe1, (byte)0x25,
            (byte)0xf1, (byte)0x17, (byte)0x18, (byte)0x19, (byte)0x1a, (byte)0x26, (byte)0x27, (byte)0x28, (byte)0x29, (byte)0x2a,
            (byte)0x35, (byte)0x36, (byte)0x37, (byte)0x38, (byte)0x39, (byte)0x3a, (byte)0x43, (byte)0x44, (byte)0x45, (byte)0x46,
            (byte)0x47, (byte)0x48, (byte)0x49, (byte)0x4a, (byte)0x53, (byte)0x54, (byte)0x55, (byte)0x56, (byte)0x57, (byte)0x58,
            (byte)0x59, (byte)0x5a, (byte)0x63, (byte)0x64, (byte)0x65, (byte)0x66, (byte)0x67, (byte)0x68, (byte)0x69, (byte)0x6a,
            (byte)0x73, (byte)0x74, (byte)0x75, (byte)0x76, (byte)0x77, (byte)0x78, (byte)0x79, (byte)0x7a, (byte)0x82, (byte)0x83,
            (byte)0x84, (byte)0x85, (byte)0x86, (byte)0x87, (byte)0x88, (byte)0x89, (byte)0x8a, (byte)0x92, (byte)0x93, (byte)0x94,
            (byte)0x95, (byte)0x96, (byte)0x97, (byte)0x98, (byte)0x99, (byte)0x9a, (byte)0xa2, (byte)0xa3, (byte)0xa4, (byte)0xa5,
            (byte)0xa6, (byte)0xa7, (byte)0xa8, (byte)0xa9, (byte)0xaa, (byte)0xb2, (byte)0xb3, (byte)0xb4, (byte)0xb5, (byte)0xb6,
            (byte)0xb7, (byte)0xb8, (byte)0xb9, (byte)0xba, (byte)0xc2, (byte)0xc3, (byte)0xc4, (byte)0xc5, (byte)0xc6, (byte)0xc7,
            (byte)0xc8, (byte)0xc9, (byte)0xca, (byte)0xd2, (byte)0xd3, (byte)0xd4, (byte)0xd5, (byte)0xd6, (byte)0xd7, (byte)0xd8,
            (byte)0xd9, (byte)0xda, (byte)0xe2, (byte)0xe3, (byte)0xe4, (byte)0xe5, (byte)0xe6, (byte)0xe7, (byte)0xe8, (byte)0xe9,
            (byte)0xea, (byte)0xf2, (byte)0xf3, (byte)0xf4, (byte)0xf5, (byte)0xf6, (byte)0xf7, (byte)0xf8, (byte)0xf9, (byte)0xfa };

}
