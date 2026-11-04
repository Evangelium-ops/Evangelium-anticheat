package ac.grim.grimac.utils.reflection;

import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LauncherProfileTest {
    private UserProfile profile(String name, TextureProperty... properties) {
        return new UserProfile(UUID.randomUUID(), name, List.of(properties));
    }

    @Test void signedLauncherMarkerIdentifiesStandaloneBedrockWithoutNameOrUuidHeuristics() {
        assertTrue(LauncherProfile.isBedrockPlayer(true, profile("AnyName", new TextureProperty("evangelium:bedrock", "v1", null))));
        assertFalse(LauncherProfile.isBedrockPlayer(true, profile(".BedrockLookingName")));
        assertFalse(LauncherProfile.isBedrockPlayer(true, profile("JavaPlayer", new TextureProperty("textures", "skin", "sig"))));
    }

    @Test void untrustedMissingUnknownOrConflictingMarkersNeverExemptJavaPlayers() {
        var marker = new TextureProperty("evangelium:bedrock", "v1", null);
        assertFalse(LauncherProfile.isBedrockPlayer(false, profile("JavaPlayer", marker)));
        assertFalse(LauncherProfile.isBedrockPlayer(true, null));
        assertFalse(LauncherProfile.isBedrockPlayer(true, profile("JavaPlayer", new TextureProperty("evangelium:bedrock", "true", null))));
        assertFalse(LauncherProfile.isBedrockPlayer(true, profile("JavaPlayer", marker, marker)));
        assertFalse(LauncherProfile.isBedrockPlayer(null));
    }
}
