package xyz.shadowmere.desktop;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import javax.swing.Icon;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bundled vector flags render consistently on Windows, macOS and Linux, without emoji fonts. */
final class FlagIcons {
    private static final Map<String,Icon> CACHE = new HashMap<>();
    static Icon get(String country) {
        String code = country == null ? "" : country.toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z]{2}") || FlagIcons.class.getResource("/flags/"+code+".svg") == null) code="xx";
        final String key=code;
        return CACHE.computeIfAbsent(key, ignored -> new FlatSVGIcon("flags/"+key+".svg",28,21));
    }
}
