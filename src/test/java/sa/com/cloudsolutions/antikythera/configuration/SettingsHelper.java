package sa.com.cloudsolutions.antikythera.configuration;

/**
 * Test helper to access protected members of Settings.
 */
public class SettingsHelper {
    public static void clear() {
        if (Settings.props != null) {
            Settings.props.clear();
        }
    }
}
