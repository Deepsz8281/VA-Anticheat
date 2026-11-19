package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.check.impl.combat.EntityAttack;

/**
 * Capability interface for checks that evaluate entity attacks (reach, through-wall, killaura,
 * autoclicker). Implementations must extend {@link Check}.
 */
public interface CombatCheck {

    void onAttack(EntityAttack attack);
}
