package se.oscarwiklund.twlan2.backend.service.npc;

import se.oscarwiklund.twlan2.backend.domain.Account;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// How an NPC names its villages (#128): "<name>'s village" (normally its home), then "<name>'s village 2", "3", ...,
// the lowest number none of its villages has. Kept within the 32 characters a player may give a village; a long
// NPC name is cut short to fit.
public final class NpcVillageNames {

    static final int MAX_LENGTH = 32;
    private static final String SUFFIX = "'s village";
    private static final Pattern OWN = Pattern.compile("(.+)" + Pattern.quote(SUFFIX) + "( \\d{1,6})?");

    private NpcVillageNames() {}

    public static String nextFor(Account npc, Collection<String> namesInUse) {
        for (int n = 1; ; n++) {
            String name = numbered(npc.getUsername(), n);
            if (!namesInUse.contains(name)) return name;
        }
    }

    // A name this scheme gives the NPC (or the one its home got when it joined, which may be longer than 32).
    public static boolean isOwnName(Account npc, String name) {
        if (name == null) return false;
        Matcher m = OWN.matcher(name);
        if (!m.matches()) return false;
        if (m.group(1).equals(npc.getUsername())) return true;
        int n = m.group(2) == null ? 1 : Integer.parseInt(m.group(2).trim());
        return name.equals(numbered(npc.getUsername(), n));
    }

    static String numbered(String owner, int n) {
        String tail = SUFFIX + (n == 1 ? "" : " " + n);
        int room = MAX_LENGTH - tail.length();
        return (owner.length() > room ? owner.substring(0, room).stripTrailing() : owner) + tail;
    }
}
