package se.oscarwiklund.twlan2.backend.service;

import org.junit.jupiter.api.Test;
import se.oscarwiklund.twlan2.backend.domain.Village;
import se.oscarwiklund.twlan2.backend.domain.World;
import se.oscarwiklund.twlan2.backend.service.npc.NpcArchetype;
import se.oscarwiklund.twlan2.backend.service.npc.NpcDifficulty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// With noblemen off, no NPC plans to conquer: it neither saves for coins nor builds an Academy for it.
class NpcNoblesOffTest {

    private static Village villageOn(World world) {
        Village v = new Village();
        v.setWorld(world);
        return v;
    }

    @Test
    void noNpcExpandsWhenNoblemenAreOff() {
        World world = new World();
        Village v = villageOn(world);
        assertTrue(NpcService.expanding(v, NpcArchetype.CONQUEROR, NpcDifficulty.of(world)));

        world.getSettings().put("noblemanSystem", "off");
        assertFalse(NpcService.expanding(v, NpcArchetype.CONQUEROR, NpcDifficulty.of(world)));
    }
}
