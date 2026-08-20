package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/** 与 framework AudioSystem 一致的 static 字段/方法，供反射实现做完整逻辑验证。 */
public final class FakeAudioSystem {
    public static final int DEVICE_STATE_AVAILABLE = 1;
    public static final int DEVICE_OUT_EARPIECE = 0x1;
    public static final int DEVICE_OUT_SPEAKER = 0x2;
    public static final int DEVICE_OUT_WIRED_HEADSET = 0x4;
    public static final int DEVICE_OUT_WIRED_HEADPHONE = 0x8;
    public static final int DEVICE_OUT_BLUETOOTH_SCO = 0x10;
    public static final int DEVICE_OUT_BLUETOOTH_A2DP = 0x80;
    public static final int DEVICE_OUT_USB_ACCESSORY = 0x2000;
    public static final int DEVICE_OUT_USB_DEVICE = 0x4000;
    public static final int DEVICE_OUT_USB_HEADSET = 0x4000000;
    public static final int DEVICE_OUT_BLE_HEADSET = 0x20000000;
    public static final int DEVICE_OUT_BLE_SPEAKER = 0x20000001;
    public static final int DEVICE_OUT_BLE_BROADCAST = 0x20000002;

    public static int lastDevice;
    public static String lastAddress;
    public static int lastUid;
    public static int[] lastDeviceIds;
    public static String[] lastDeviceAddresses;
    public static int connectionStateResult = DEVICE_STATE_AVAILABLE;
    public static int setAffinityResult = 0;
    public static int removeAffinityResult = 0;
    public static int devicesForStreamResult = 0;
    public static RuntimeException throwOnGet;
    public static RuntimeException throwOnSet;
    public static RuntimeException throwOnRemove;

    private FakeAudioSystem() {
    }

    public static void reset() {
        lastDevice = 0;
        lastAddress = null;
        lastUid = 0;
        lastDeviceIds = null;
        lastDeviceAddresses = null;
        connectionStateResult = DEVICE_STATE_AVAILABLE;
        setAffinityResult = 0;
        removeAffinityResult = 0;
        devicesForStreamResult = 0;
        throwOnGet = null;
        throwOnSet = null;
        throwOnRemove = null;
    }

    public static int getDeviceConnectionState(int device, String deviceAddress) {
        if (throwOnGet != null) {
            throw throwOnGet;
        }
        lastDevice = device;
        lastAddress = deviceAddress;
        return connectionStateResult;
    }

    public static int setUidDeviceAffinities(int uid, int[] deviceIds, String[] deviceAddresses) {
        if (throwOnSet != null) {
            throw throwOnSet;
        }
        lastUid = uid;
        lastDeviceIds = deviceIds;
        lastDeviceAddresses = deviceAddresses;
        return setAffinityResult;
    }

    public static int removeUidDeviceAffinities(int uid) {
        if (throwOnRemove != null) {
            throw throwOnRemove;
        }
        lastUid = uid;
        return removeAffinityResult;
    }

    public static int getDevicesForStream(int streamType) {
        return devicesForStreamResult;
    }
}
