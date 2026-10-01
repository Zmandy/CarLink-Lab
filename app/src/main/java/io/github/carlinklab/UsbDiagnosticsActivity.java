package io.github.carlinklab;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public final class UsbDiagnosticsActivity extends ComponentActivity {
    private static final String ACTION_USB_PERMISSION = "io.github.carlinklab.USB_PERMISSION";

    private final StringBuilder log = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private UsbManager usbManager;
    private TextView logView;
    private boolean receiverRegistered;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                Parcelable extra = getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY);
                append("USB Accessory attached");
                if (extra instanceof UsbAccessory) {
                    append(describeAccessory((UsbAccessory) extra));
                } else {
                    append("Accessory payload is unavailable");
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                append("USB Accessory detached");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Parcelable extra = getParcelableExtra(intent, UsbManager.EXTRA_DEVICE);
                append("USB Device attached");
                if (extra instanceof UsbDevice) {
                    append(describeDevice((UsbDevice) extra));
                } else {
                    append("Device payload is unavailable");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                append("USB Device detached");
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                append("USB permission result received");
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_diagnostics);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        logView = findViewById(R.id.usb_log);
        Button refresh = findViewById(R.id.usb_refresh);
        Button copy = findViewById(R.id.usb_copy);
        Button clear = findViewById(R.id.usb_clear);

        refresh.setOnClickListener(view -> refreshSnapshot());
        copy.setOnClickListener(view -> copyLog());
        clear.setOnClickListener(view -> {
            log.setLength(0);
            render();
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        receiverRegistered = true;

        append("Tucson CarLife USB diagnostics started");
        refreshSnapshot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (usbManager != null) {
            refreshSnapshot();
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private void refreshSnapshot() {
        append("--- snapshot " + timeFormat.format(new Date()) + " ---");
        if (usbManager == null) {
            append("UsbManager unavailable");
            return;
        }

        UsbAccessory[] accessories = usbManager.getAccessoryList();
        if (accessories == null || accessories.length == 0) {
            append("Accessory list: empty");
        } else {
            for (UsbAccessory accessory : accessories) {
                append(describeAccessory(accessory));
            }
        }

        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            append("Device list: empty");
        } else {
            for (UsbDevice device : devices.values()) {
                append(describeDevice(device));
            }
        }
    }

    private String describeAccessory(UsbAccessory accessory) {
        return "Accessory:"
                + "\n  manufacturer=" + accessory.getManufacturer()
                + "\n  model=" + accessory.getModel()
                + "\n  description=" + accessory.getDescription()
                + "\n  version=" + accessory.getVersion()
                + "\n  serial=" + accessory.getSerial();
    }

    private String describeDevice(UsbDevice device) {
        StringBuilder text = new StringBuilder();
        text.append("Device:")
                .append("\n  name=").append(device.getDeviceName())
                .append("\n  vendorId=0x").append(toHex(device.getVendorId()))
                .append("\n  productId=0x").append(toHex(device.getProductId()))
                .append("\n  class=0x").append(toHex(device.getDeviceClass()))
                .append(" subclass=0x").append(toHex(device.getDeviceSubclass()))
                .append(" protocol=0x").append(toHex(device.getDeviceProtocol()))
                .append("\n  interfaces=").append(device.getInterfaceCount());

        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            text.append("\n  interface[").append(index).append("]")
                    .append(" id=").append(usbInterface.getId())
                    .append(" class=0x").append(toHex(usbInterface.getInterfaceClass()))
                    .append(" subclass=0x").append(toHex(usbInterface.getInterfaceSubclass()))
                    .append(" protocol=0x").append(toHex(usbInterface.getInterfaceProtocol()))
                    .append(" endpoints=").append(usbInterface.getEndpointCount());

            for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                text.append("\n    endpoint[").append(endpointIndex).append("]")
                        .append(" address=0x").append(toHex(endpoint.getAddress()))
                        .append(" type=").append(endpointType(endpoint.getType()))
                        .append(" direction=").append(endpoint.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT")
                        .append(" maxPacket=").append(endpoint.getMaxPacketSize())
                        .append(" interval=").append(endpoint.getInterval());
            }
        }
        return text.toString();
    }

    private static String endpointType(int type) {
        if (type == UsbConstants.USB_ENDPOINT_XFER_CONTROL) {
            return "CONTROL";
        }
        if (type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            return "ISOCHRONOUS";
        }
        if (type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
            return "BULK";
        }
        if (type == UsbConstants.USB_ENDPOINT_XFER_INT) {
            return "INTERRUPT";
        }
        return "UNKNOWN(" + type + ")";
    }

    private static String toHex(int value) {
        return String.format(Locale.US, "%04X", value);
    }

    private static Parcelable getParcelableExtra(Intent intent, String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(name, Parcelable.class);
        }
        return intent.getParcelableExtra(name);
    }

    private void append(String message) {
        log.append('[')
                .append(timeFormat.format(new Date()))
                .append("] ")
                .append(message)
                .append('\n');
        render();
    }

    private void render() {
        logView.setText(log.toString());
        logView.post(() -> {
            int scrollAmount = logView.getLayout() == null
                    ? 0
                    : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
            logView.scrollTo(0, Math.max(scrollAmount, 0));
        });
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("CarLife USB diagnostics", log.toString()));
        append("Log copied to clipboard");
    }
}