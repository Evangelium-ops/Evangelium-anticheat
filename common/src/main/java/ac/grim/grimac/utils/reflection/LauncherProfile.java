package ac.grim.grimac.utils.reflection;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;

/** KnowledgeLauncher sets this property inside its HMAC-authenticated Velocity forwarding payload. */
public final class LauncherProfile {
    private static final String BEDROCK = "evangelium:bedrock";

    private LauncherProfile() {
    }

    public static boolean isBedrockPlayer(User user) {
        return user != null && isBedrockPlayer(Boolean.getBoolean("launcher.licensed"), user.getProfile());
    }

    static boolean isBedrockPlayer(boolean verifiedLauncher, UserProfile profile) {
        if (!verifiedLauncher || profile == null || profile.getTextureProperties() == null) return false;
        int markers = 0;
        for (var property : profile.getTextureProperties()) {
            if (BEDROCK.equals(property.getName())) {
                if (!"v1".equals(property.getValue())) return false;
                markers++;
            }
        }
        return markers == 1;
    }
}
