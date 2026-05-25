package dev.runelite.mcp.api.snapshot;

import java.util.List;

public final class PlayerSnapshot {
    public final String name;
    public final int worldX, worldY, plane;
    public final int combatLevel;
    public final int animation;
    public final boolean moving, animating, idle;

    // Health
    public final int currentHealth, maxHealth;
    public final int healthRatio, healthScale;

    // Resources
    public final int prayerPoints, maxPrayerPoints;
    public final int runEnergy;        // 0-10000
    public final int specialAttackEnergy; // 0-100
    public final boolean runEnabled;

    // Status effects
    public final boolean poisoned, venomed;
    public final boolean staminaBoosted, antifired, superAntifired;
    public final boolean antiPoisoned, antiVenomed;

    // Appearance
    public final int overheadIcon, skullIcon;
    public final int weaponId;

    // Interaction
    public final boolean interacting;
    public final int interactingIndex;
    public final String interactingType; // "npc", "player", or null
    public final String interactingName; // name of target entity

    // Active prayers (list of prayer IDs or names)
    public final List<String> activePrayers;

    public final List<HitsplatData> recentHitsplats;

    public PlayerSnapshot(String name, int worldX, int worldY, int plane,
                          int combatLevel, int animation,
                          boolean moving, boolean animating, boolean idle,
                          int currentHealth, int maxHealth,
                          int healthRatio, int healthScale,
                          int prayerPoints, int maxPrayerPoints,
                          int runEnergy, int specialAttackEnergy, boolean runEnabled,
                          boolean poisoned, boolean venomed,
                          boolean staminaBoosted, boolean antifired, boolean superAntifired,
                          boolean antiPoisoned, boolean antiVenomed,
                          int overheadIcon, int skullIcon, int weaponId,
                          boolean interacting, int interactingIndex,
                          String interactingType, String interactingName,
                          List<String> activePrayers,
                          List<HitsplatData> recentHitsplats) {
        this.name = name;
        this.worldX = worldX; this.worldY = worldY; this.plane = plane;
        this.combatLevel = combatLevel; this.animation = animation;
        this.moving = moving; this.animating = animating; this.idle = idle;
        this.currentHealth = currentHealth; this.maxHealth = maxHealth;
        this.healthRatio = healthRatio; this.healthScale = healthScale;
        this.prayerPoints = prayerPoints; this.maxPrayerPoints = maxPrayerPoints;
        this.runEnergy = runEnergy; this.specialAttackEnergy = specialAttackEnergy;
        this.runEnabled = runEnabled;
        this.poisoned = poisoned; this.venomed = venomed;
        this.staminaBoosted = staminaBoosted;
        this.antifired = antifired; this.superAntifired = superAntifired;
        this.antiPoisoned = antiPoisoned; this.antiVenomed = antiVenomed;
        this.overheadIcon = overheadIcon; this.skullIcon = skullIcon;
        this.weaponId = weaponId;
        this.interacting = interacting; this.interactingIndex = interactingIndex;
        this.interactingType = interactingType; this.interactingName = interactingName;
        this.activePrayers = activePrayers;
        this.recentHitsplats = recentHitsplats;
    }
}
