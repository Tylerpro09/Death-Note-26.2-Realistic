# Death Note 26.2 Realistic — v3 Canon Rules

This version separates the normal **CANON** player mode from the experimental **CORRUPT** entity/block modes.

## Implemented gameplay rules

- Writing a valid online player's name schedules their Death Note death.
- The default/fallback cause is heart attack.
- The writer must currently own the Death Note.
- The first canon user becomes the owner.
- Ownership can be transferred to another online player from the Death Note screen.
- The writer must have seen the target recently. Minecraft simulates remembering the face/identity by recording players seen within 32 blocks for 5 minutes.
- A Death Note victim remains condemned after death and cannot remain alive after respawn until restored.
- Condemned players persist across server restarts.
- Shinigami Eyes can be obtained through a dedicated deal button.
- The deal halves the owner's simulated remaining lifespan.
- With Shinigami Eyes, the nearest player within 16 blocks periodically reveals name + simulated lifespan.
- Ownership, Shinigami Eyes and simulated lifespans persist in `deathnote_realistic/canon_state.json` inside the world folder.
- Existing block condemnation/restoration data persists separately in `deathnote_realistic/state.json`.

## Canon vs corrupt modes

### CANON: Human player

Uses ownership, remembered identity, cause selection, 40-second scheduling and the condemned-player respawn rules.

### CORRUPT: Entity

Experimental non-canon feature retained from v2. It can erase nearby Minecraft entities.

### CORRUPT: Block

Experimental non-canon feature retained from v2. It can condemn block IDs globally in active areas and restore blocks recorded by the mod.

## Rules represented as simulation

Some source-material rules cannot map literally to Minecraft, so v3 uses gameplay equivalents:

- "Remember the face" => player identity was seen recently in-game.
- "Remaining lifespan" => a persistent simulated lifespan value.
- "Shinigami Eyes" => periodic server-side identity/lifespan reveal for nearby players.
- Physical possibility => currently constrained to the built-in death causes; unknown cause IDs fall back to heart attack.

## Planned canon expansion

- Circumstance editor and explicit scheduled time.
- Rich physical-possibility validator for circumstances.
- Loan vs full ownership transfer.
- Memory loss/recovery mechanics after relinquishing/reclaiming ownership.
- Shinigami entity linked to each Death Note.
- Touch-to-see/hear-Shinigami mechanic.
- More complete lifespan simulation and Shinigami-death rules.
- Per-item Death Note identity so multiple notebooks can have separate owners and Shinigami.
