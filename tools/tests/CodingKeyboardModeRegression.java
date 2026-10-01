import com.baystudio.droide.core.CodingKeyboardMode;
import java.util.HashSet;
import java.util.Set;


public final class CodingKeyboardModeRegression {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        check(CodingKeyboardMode.DEFAULT == CodingKeyboardMode.COMPACT,
                "New users must get Compact");
        check(CodingKeyboardMode.fromStoredValue(null) == CodingKeyboardMode.COMPACT,
                "Upgrades without a preference must get Compact");
        for (String invalid : new String[] {"", "unknown", "COMPACT", "compact ", "future_mode"}) {
            check(CodingKeyboardMode.fromStoredValue(invalid) == CodingKeyboardMode.COMPACT,
                    "Invalid/future preference must fall back to Compact");
        }
        Set<String> ids = new HashSet<>();
        for (CodingKeyboardMode mode : CodingKeyboardMode.values()) {
            check(ids.add(mode.getStorageValue()), "Preference IDs must be unique");
            check(CodingKeyboardMode.fromStoredValue(mode.getStorageValue()) == mode,
                    "Every saved preference must restore the same mode");
            check(!mode.requestsRawInput() || !mode.allowsWordCorrections(),
                    "Raw input must never request word corrections");
        }
        check("compact".equals(CodingKeyboardMode.COMPACT.getStorageValue()),
                "Compact preference ID must remain stable");
        check("no_corrections".equals(CodingKeyboardMode.NO_CORRECTIONS.getStorageValue()),
                "No corrections preference ID must remain stable");
        check("normal".equals(CodingKeyboardMode.NORMAL.getStorageValue()),
                "Normal preference ID must remain stable");
        check(CodingKeyboardMode.COMPACT.requestsRawInput(), "Compact must request raw input");
        check(!CodingKeyboardMode.COMPACT.allowsWordCorrections(), "Compact must disable corrections");
        check(!CodingKeyboardMode.NO_CORRECTIONS.requestsRawInput(), "Language input must remain available");
        check(!CodingKeyboardMode.NO_CORRECTIONS.allowsWordCorrections(), "Compatibility mode must disable corrections");
        check(!CodingKeyboardMode.NORMAL.requestsRawInput(), "Normal must use text input");
        check(CodingKeyboardMode.NORMAL.allowsWordCorrections(), "Normal must allow corrections");
        System.out.println("CODING_KEYBOARD_POLICY_OK checks=" + checks);
    }
}
