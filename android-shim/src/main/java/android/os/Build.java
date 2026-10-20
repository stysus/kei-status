package android.os;

/**
 * Stand-in for android.os.Build.
 *
 * Written in Java on purpose: extension bytecode does `getstatic android/os/Build$VERSION.SDK_INT`
 * and `getstatic android/os/Build.VERSION`, so the nested type must literally be named `VERSION`
 * and the fields must be static. Kotlin cannot express a nested class and a property with the same
 * name, and its `object` would emit an INSTANCE field instead of the static members Android has.
 */
public final class Build {

    public static final VERSION VERSION = new VERSION();

    public static final String UNKNOWN = "unknown";

    public static final String MANUFACTURER = "kei-status";
    public static final String BRAND = "kei-status";
    public static final String MODEL = "jvm-harness";
    public static final String DEVICE = "jvm-harness";
    public static final String PRODUCT = "jvm-harness";
    public static final String BOARD = "jvm-harness";
    public static final String HARDWARE = "jvm-harness";
    public static final String FINGERPRINT = "kei-status/jvm-harness/jvm-harness:14/KEI/1:user/release-keys";
    public static final String ID = "KEI";
    public static final String DISPLAY = "KEI";
    public static final String TYPE = "user";
    public static final String TAGS = "release-keys";
    public static final String BOOTLOADER = "unknown";
    public static final String HOST = "localhost";
    public static final String USER = "kei-status";
    public static final String SERIAL = "unknown";
    public static final String RADIO = "unknown";
    public static final long TIME = 0L;

    private Build() {
    }

    public static final class VERSION {

        public static final int SDK_INT = 34;
        public static final String RELEASE = "14";
        public static final String CODENAME = "REL";
        public static final String INCREMENTAL = "1";
        public static final int PREVIEW_SDK_INT = 0;
        public static final int RESOURCES_SDK_INT = SDK_INT;

        private VERSION() {
        }
    }

    public static final class VERSION_CODES {

        public static final int LOLLIPOP = 21;
        public static final int M = 23;
        public static final int N = 24;
        public static final int O = 26;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;

        private VERSION_CODES() {
        }
    }
}
