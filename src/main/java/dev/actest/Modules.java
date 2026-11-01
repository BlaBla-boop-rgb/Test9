package dev.actest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Die Testfunktionen. Zweck: dem Anticheat des eigenen Servers verdächtiges Verhalten liefern.
 * Alle Funktionsschalter sind nur im Speicher und beim Start immer aus.
 */
public final class Modules {

    private Modules() {
    }

    public static boolean autoHit = false;
    public static boolean autoTarget = false;
    public static boolean bhop = false;
    public static boolean autoTotem = false;
    public static boolean autoSprint = false;
    public static boolean fly = false;
    public static boolean scaffold = false;

    /** Trigger-Bot: schlägt, sobald das Fadenkreuz auf einem Ziel liegt (Cooldown beachtet). */
    public static boolean trigger = false;

    /** Combo-Modus: steuert Blick und Bewegung, um einen Gegner im Dauerhit zu halten. */
    public static boolean combo = false;
    /** false = 1.8-Stil (Spam-Hits, kein Cooldown), true = 1.21-Stil (Cooldown, Crits). */
    public static boolean comboNew = false;
    /** Trigger: wechselt zur Axt und schlägt, wenn das Ziel blockt. */
    public static boolean trigShield = false;
    /** Trigger: setzt Spinnweben auf die Füße des Ziels. */
    public static boolean trigWeb = false;
    /** Trigger: setzt Lava auf die Füße des Ziels und sammelt sie wieder ein. */
    public static boolean trigLava = false;
    /** Trigger: sammelt Wasserquellen in der Nähe mit einem leeren Eimer ein. */
    public static boolean trigDrain = false;

    /** Ziele für Auto-Hit / Auto-Target. */
    public static boolean targetPlayers = true;
    public static boolean targetMobs = true;

    /** Normale Sprintgeschwindigkeit in Blöcken pro Tick (ca. 5,6 Blöcke/s). */
    private static final double BASE_SPEED = 0.28;
    /** Normale Fluggeschwindigkeit (Kreativmodus) in Blöcken pro Tick. */
    private static final float BASE_FLY_SPEED = 0.05f;

    private static int hitTicks = 0;
    private static int totemTicks = 0;
    private static boolean jumpForced = false;
    private static boolean warned = false;
    private static int trigCd = 0;
    private static boolean comboKeys = false;
    private static int comboHitCd = 0;
    private static int comboWait = 0;
    private static int comboWtap = 0;
    private static int comboStrafeT = 0;
    private static int comboStrafeDir = 1;
    private static final java.util.Random RNG = new java.util.Random();
    private static int drainTicks = 0;
    private static BlockPos lavaPos = null;
    private static int lavaAge = 0;

    /** Ob wir den Flugmodus selbst eingeschaltet haben, um ihn später korrekt zurückzunehmen. */
    private static boolean flyAppliedByUs = false;

    public static boolean anyOn() {
        return autoHit || autoTarget || bhop || autoTotem || autoSprint || fly || scaffold
                || combo || trigger || trigShield || trigWeb || trigLava || trigDrain;
    }

    public static void disableAll() {
        autoHit = false;
        autoTarget = false;
        bhop = false;
        autoTotem = false;
        autoSprint = false;
        hitTicks = 0;
        fly = false;
        scaffold = false;
        combo = false;
        trigger = false;
        trigShield = false;
        trigWeb = false;
        trigLava = false;
        trigDrain = false;
        lavaPos = null;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            disableAll();
            return;
        }

        if (!Guard.allowed(mc)) {
            if (anyOn()) {
                disableAll();
                if (!warned) {
                    warned = true;
                    p.sendSystemMessage(Component.literal(
                            "[ACTest] Gesperrt: Dieser Server steht nicht in config/actest.json (allowedServers)."));
                }
            }
            releaseJump(mc);
            releaseComboKeys(mc);
            applyFly(p);
            return;
        }
        warned = false;
        applyFly(p);

        // Bei offenem Menü/Chat nichts tun
        if (mc.screen != null) {
            releaseJump(mc);
            return;
        }

        boolean fwd = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();
        boolean manualMove = fwd || back || left || right;

        if (autoSprint && fwd) {
            mc.options.keySprint.setDown(true);
        }

        if (bhop && manualMove) {
            if (p.onGround()) {
                mc.options.keyJump.setDown(true);
                jumpForced = true;
            } else {
                releaseJump(mc);
            }
            boost(p, fwd, back, left, right);
        } else {
            releaseJump(mc);
        }

        if (autoTotem && ++totemTicks >= 4) {
            totemTicks = 0;
            tryTotem(mc, p);
        }

        LivingEntity nearest = autoTarget ? nearestTarget(mc, p) : null;
        if (autoTarget && nearest != null && !manualMove) {
            // Nur steuern, solange der Spieler nicht selbst läuft: wir übernehmen die Bewegung.
            followTarget(p, nearest);
        }

        if (scaffold) {
            tryScaffold(mc, p);
        }

        comboMode(mc, p);
        triggerBot(mc, p);

        if (autoHit) {
            hitTicks++;
            autoHit(mc, p);
        } else {
            hitTicks = 0;
        }
    }

    /**
     * Scaffold (verstärkt): sucht selbst einen Block in der Hotbar (und stellt den Slot danach zurück),
     * setzt bis zu "scaffoldPerTick" Blöcke pro Tick, auch am Boden, und baut zusätzlich
     * "scaffoldAhead" Blöcke in Laufrichtung voraus. Beim Springen entsteht so ein Turm.
     */
    private static void tryScaffold(Minecraft mc, LocalPlayer p) {
        ActestConfig cfg = ActestConfig.get();
        int slot = findBlockSlot(p);
        if (slot < 0) return;

        List<BlockPos> targets = new ArrayList<>();
        targets.add(BlockPos.containing(p.getX(), p.getY() - 0.1, p.getZ()));

        Vec3 mv = p.getDeltaMovement();
        double hx = mv.x, hz = mv.z;
        if (hx * hx + hz * hz < 0.0004) {
            Vec3 look = p.getLookAngle();
            hx = look.x;
            hz = look.z;
        }
        double hl = Math.sqrt(hx * hx + hz * hz);
        if (hl > 1.0e-4) {
            hx /= hl;
            hz /= hl;
            for (int i = 1; i <= cfg.scaffoldAhead; i++) {
                targets.add(BlockPos.containing(p.getX() + hx * i, p.getY() - 0.1, p.getZ() + hz * i));
            }
        }

        int prev = p.getInventory().getSelectedSlot();
        boolean switched = prev != slot;
        if (switched) p.getInventory().setSelectedSlot(slot);

        int placed = 0;
        for (BlockPos target : targets) {
            if (placed >= cfg.scaffoldPerTick) break;
            if (placeAt(mc, p, target)) placed++;
        }
        if (placed > 0) p.swing(InteractionHand.MAIN_HAND);

        if (switched) p.getInventory().setSelectedSlot(prev);
    }

    private static int findBlockSlot(LocalPlayer p) {
        Inventory inv = p.getInventory();
        if (inv.getItem(inv.getSelectedSlot()).getItem() instanceof BlockItem) return inv.getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() instanceof BlockItem) return i;
        }
        return -1;
    }

    /** Setzt einen Block an "target", indem eine angrenzende feste Fläche angeklickt wird. */
    private static boolean placeAt(Minecraft mc, LocalPlayer p, BlockPos target) {
        BlockState ts = mc.level.getBlockState(target);
        if (!ts.isAir() && !ts.canBeReplaced()) return false;

        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction d : order) {
            BlockPos n = target.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || !ns.getFluidState().isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3 hitPos = new Vec3(
                    n.getX() + 0.5 + face.getStepX() * 0.5,
                    n.getY() + 0.5 + face.getStepY() * 0.5,
                    n.getZ() + 0.5 + face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hitPos, face, n, false));
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Combo-Modus

    private static void releaseComboKeys(Minecraft mc) {
        if (!comboKeys) return;
        comboKeys = false;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private static LivingEntity comboTarget(Minecraft mc, LocalPlayer p) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(8.0), e -> isTarget(e, p, 64.0))) {
            double d = p.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    /**
     * Zielt weich auf den nächsten Gegner, hält ihn auf Abstand, strafet zufällig, tippt W (Sprint-Reset)
     * nach jedem Treffer und schlägt je nach Modus im 1.8-Takt (ca. 8-10 CPS) oder im 1.21-Takt
     * (Cooldown abwarten, bevorzugt beim Fallen = Crit).
     */
    private static void comboMode(Minecraft mc, LocalPlayer p) {
        if (!combo) {
            releaseComboKeys(mc);
            return;
        }
        LivingEntity t = comboTarget(mc, p);
        if (t == null) {
            releaseComboKeys(mc);
            return;
        }
        ActestConfig cfg = ActestConfig.get();

        // Blick: weich nachführen, maximal "comboAim" Grad pro Tick
        Vec3 eye = p.getEyePosition();
        double dx = t.getX() - eye.x;
        double dy = t.getY() + t.getBbHeight() * 0.7 - eye.y;
        double dz = t.getZ() - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        float step = cfg.comboAim;
        float dYaw = (float) Mth_wrapDegrees(wantYaw - p.getYRot());
        float dPitch = wantPitch - p.getXRot();
        p.setYRot(p.getYRot() + Math.max(-step, Math.min(step, dYaw)));
        p.setXRot(Math.max(-90f, Math.min(90f, p.getXRot() + Math.max(-step, Math.min(step, dPitch)))));

        // Bewegung
        double dist = p.distanceTo(t);
        if (comboWtap > 0) comboWtap--;
        boolean forward = dist > (comboNew ? 2.6 : 2.2) && comboWtap == 0;
        boolean backward = dist < 1.3;
        if (--comboStrafeT <= 0) {
            comboStrafeT = 8 + RNG.nextInt(14);
            comboStrafeDir = RNG.nextBoolean() ? 1 : -1;
        }
        boolean strafe = dist < 4.0;
        mc.options.keyUp.setDown(forward);
        mc.options.keyDown.setDown(backward);
        mc.options.keyLeft.setDown(strafe && comboStrafeDir > 0);
        mc.options.keyRight.setDown(strafe && comboStrafeDir < 0);
        mc.options.keySprint.setDown(forward);
        mc.options.keyJump.setDown(comboNew && p.onGround() && dist < 3.8);
        comboKeys = true;

        // Angriff
        if (comboHitCd > 0) comboHitCd--;
        if (dist > 3.0 || comboHitCd > 0 || Math.abs(dYaw) > 20f) return;

        boolean hit;
        if (!comboNew) {
            hit = true;
        } else if (p.getAttackStrengthScale(0.5f) < 0.95f) {
            comboWait = 0;
            hit = false;
        } else {
            boolean falling = p.getDeltaMovement().y < 0 && !p.onGround();
            hit = falling || ++comboWait > 6;
        }
        if (!hit) return;

        int sword = findSlot(p, s -> s.is(ItemTags.SWORDS));
        if (sword >= 0) {
            withSlot(p, sword, () -> {
                mc.gameMode.attack(p, t);
                return true;
            });
        } else {
            mc.gameMode.attack(p, t);
        }
        p.swing(InteractionHand.MAIN_HAND);
        comboHitCd = comboNew ? 1 : 2 + RNG.nextInt(2);
        comboWait = 0;
        comboWtap = 1;
    }

    // ---------------------------------------------------------------- Trigger-Bot

    private static int findSlot(LocalPlayer p, Predicate<ItemStack> match) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (match.test(inv.getItem(i))) return i;
        }
        return -1;
    }

    /** Wechselt kurz auf einen Hotbar-Slot, führt die Aktion aus und stellt den alten Slot wieder ein. */
    private static boolean withSlot(LocalPlayer p, int slot, BooleanSupplier action) {
        int prev = p.getInventory().getSelectedSlot();
        if (prev != slot) p.getInventory().setSelectedSlot(slot);
        boolean ok = action.getAsBoolean();
        if (prev != slot) p.getInventory().setSelectedSlot(prev);
        return ok;
    }

    /** Dreht den Blick kurz auf "pt", benutzt das Item in der Hand (Eimer) und dreht zurück. */
    private static boolean aimUse(Minecraft mc, LocalPlayer p, Vec3 pt) {
        float oy = p.getYRot();
        float ox = p.getXRot();
        Vec3 eye = p.getEyePosition();
        double dx = pt.x - eye.x, dy = pt.y - eye.y, dz = pt.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, h)));
        InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        p.setYRot(oy);
        p.setXRot(ox);
        return r.consumesAction();
    }

    private static void triggerBot(Minecraft mc, LocalPlayer p) {
        if (trigCd > 0) trigCd--;
        if (lavaPos != null) lavaAge++;

        if (trigLava || trigDrain) cleanup(mc, p);

        if (!trigger) return;
        if (!(mc.hitResult instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof LivingEntity target) || !isTarget(target, p, 36.0)) return;

        // Schild brechen
        if (trigShield && target.isBlocking()) {
            int axe = findSlot(p, s -> s.is(ItemTags.AXES));
            if (axe >= 0 && p.getAttackStrengthScale(0.5f) >= 0.95f) {
                withSlot(p, axe, () -> {
                    mc.gameMode.attack(p, target);
                    return true;
                });
                p.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }

        // Spinnweben auf die Füße
        if (trigWeb && trigCd == 0) {
            int web = findSlot(p, s -> s.is(Items.COBWEB));
            if (web >= 0 && withSlot(p, web, () -> placeAt(mc, p, target.blockPosition()))) {
                p.swing(InteractionHand.MAIN_HAND);
                trigCd = 20;
                return;
            }
        }

        // Lava auf die Füße (Eimer zielt auf den Boden unter dem Ziel)
        if (trigLava && trigCd == 0 && lavaPos == null) {
            int lava = findSlot(p, s -> s.is(Items.LAVA_BUCKET));
            if (lava >= 0) {
                Vec3 pt = new Vec3(target.getX(), target.getY() - 0.05, target.getZ());
                if (withSlot(p, lava, () -> aimUse(mc, p, pt))) {
                    lavaPos = target.blockPosition();
                    lavaAge = 0;
                    trigCd = 20;
                    return;
                }
            }
        }

        // Normaler Treffer, sobald der Angriffs-Cooldown bereit ist
        if (p.getAttackStrengthScale(0.5f) >= 0.95f) {
            int sword = findSlot(p, s -> s.is(ItemTags.SWORDS));
            if (sword >= 0) {
                withSlot(p, sword, () -> {
                    mc.gameMode.attack(p, target);
                    return true;
                });
            } else {
                mc.gameMode.attack(p, target);
            }
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Sammelt die eigene Lava wieder ein und leert Wasserquellen in Reichweite mit einem leeren Eimer. */
    private static void cleanup(Minecraft mc, LocalPlayer p) {
        if (trigCd > 0) return;
        int bucket = findSlot(p, s -> s.is(Items.BUCKET));
        if (bucket < 0) return;

        if (trigLava && lavaPos != null && lavaAge >= 30) {
            BlockPos lp = lavaPos;
            lavaPos = null;
            var fs = mc.level.getBlockState(lp).getFluidState();
            if (fs.is(FluidTags.LAVA) && fs.isSource() && p.getEyePosition().distanceToSqr(Vec3.atCenterOf(lp)) < 16.0) {
                withSlot(p, bucket, () -> aimUse(mc, p, Vec3.atCenterOf(lp)));
                trigCd = 10;
                return;
            }
        }

        if (!trigDrain || ++drainTicks < 5) return;
        drainTicks = 0;
        BlockPos base = p.blockPosition();
        BlockPos best = null;
        double bestD = 16.0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos bp = base.offset(dx, dy, dz);
                    var fs = mc.level.getBlockState(bp).getFluidState();
                    if (!fs.is(FluidTags.WATER) || !fs.isSource()) continue;
                    double d = p.getEyePosition().distanceToSqr(Vec3.atCenterOf(bp));
                    if (d < bestD) {
                        bestD = d;
                        best = bp;
                    }
                }
            }
        }
        if (best != null) {
            Vec3 c = Vec3.atCenterOf(best);
            withSlot(p, bucket, () -> aimUse(mc, p, c));
            trigCd = 6;
        }
    }

    private static void releaseJump(Minecraft mc) {
        if (jumpForced) {
            mc.options.keyJump.setDown(false);
            jumpForced = false;
        }
    }

    /** Schaltet Fliegen über die normalen Fähigkeiten des Spielers (wie Kreativmodus) ein oder aus. */
    private static void applyFly(LocalPlayer p) {
        if (fly) {
            if (!p.getAbilities().mayfly) {
                p.getAbilities().mayfly = true;
                flyAppliedByUs = true;
            }
            p.getAbilities().flying = true;
            p.getAbilities().setFlyingSpeed(BASE_FLY_SPEED * (ActestConfig.get().flySpeedTenths / 10.0f));
            p.onUpdateAbilities();
        } else if (flyAppliedByUs) {
            p.getAbilities().flying = false;
            p.getAbilities().mayfly = false;
            p.getAbilities().setFlyingSpeed(BASE_FLY_SPEED);
            p.onUpdateAbilities();
            flyAppliedByUs = false;
        }
    }

    /** Setzt die horizontale Geschwindigkeit auf (BASE_SPEED * Faktor) in Laufrichtung. */
    private static void boost(LocalPlayer p, boolean fwd, boolean back, boolean left, boolean right) {
        if (p.isInWater()) return;

        double forward = (fwd ? 1 : 0) - (back ? 1 : 0);
        double strafe = (left ? 1 : 0) - (right ? 1 : 0); // links = positiv
        double len = Math.sqrt(forward * forward + strafe * strafe);
        if (len == 0) return;
        forward /= len;
        strafe /= len;

        double yaw = Math.toRadians(p.getYRot());
        double dx = -Math.sin(yaw) * forward + Math.cos(yaw) * strafe;
        double dz = Math.cos(yaw) * forward + Math.sin(yaw) * strafe;

        double speed = BASE_SPEED * (ActestConfig.get().bhopSpeedTenths / 10.0);
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(dx * speed, v.y, dz * speed);
    }

    /** Läuft automatisch auf das Ziel zu und dreht den Blick sanft dorthin. */
    private static void followTarget(LocalPlayer p, LivingEntity target) {
        Vec3 to = target.position().subtract(p.position());
        double distSq = to.x * to.x + to.z * to.z;

        // Blickrichtung sanft nachziehen
        double targetYaw = Math.toDegrees(Math.atan2(-to.x, to.z));
        float yawDiff = (float) Mth_wrapDegrees(targetYaw - p.getYRot());
        p.setYRot(p.getYRot() + yawDiff * 0.35f);

        double stopDistSq = 2.5 * 2.5; // nah genug dran: nicht mehr reinlaufen
        if (distSq > stopDistSq) {
            double yaw = Math.toRadians(p.getYRot());
            double speed = BASE_SPEED;
            double dx = -Math.sin(yaw) * speed;
            double dz = Math.cos(yaw) * speed;
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(dx, v.y, dz);
        }
    }

    private static double Mth_wrapDegrees(double d) {
        double r = d % 360.0;
        if (r >= 180.0) r -= 360.0;
        if (r < -180.0) r += 360.0;
        return r;
    }

    private static boolean isTarget(Entity e, LocalPlayer p, double rangeSq) {
        if (e == p || !(e instanceof LivingEntity) || !((LivingEntity) e).isAlive()) return false;
        if (e instanceof Player) {
            if (!targetPlayers || e.isSpectator()) return false;
        } else if (e instanceof Mob) {
            if (!targetMobs) return false;
        } else {
            return false; // z. B. Rüstungsständer
        }
        return p.distanceToSqr(e) <= rangeSq;
    }

    private static LivingEntity nearestTarget(Minecraft mc, LocalPlayer p) {
        double range = ActestConfig.get().hitRangeTenths / 10.0;
        double rangeSq = range * range;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(range), e -> isTarget(e, p, rangeSq))) {
            double d = p.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    /**
     * Schlägt bis zu maxTargets Ziele gleichzeitig: ohne Blickrichtung, ohne Sichtlinie (auch durch Wände).
     * Ziele sind Spieler und/oder Mobs im eingestellten Abstand, die nächsten zuerst.
     */
    private static void autoHit(Minecraft mc, LocalPlayer p) {
        ActestConfig cfg = ActestConfig.get();
        if (hitTicks < cfg.hitInterval) return;

        double range = cfg.hitRangeTenths / 10.0;
        double rangeSq = range * range;
        List<Entity> found = new ArrayList<>(
                mc.level.getEntities(p, p.getBoundingBox().inflate(range), e -> isTarget(e, p, rangeSq)));
        if (found.isEmpty()) return;

        found.sort((a, b) -> Double.compare(p.distanceToSqr(a), p.distanceToSqr(b)));

        hitTicks = 0;
        int count = Math.min(cfg.maxTargets, found.size());
        for (int i = 0; i < count; i++) {
            mc.gameMode.attack(p, found.get(i));
        }
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** Legt einen Totem aus dem Inventar in die zweite Hand, falls dort keiner liegt. */
    private static void tryTotem(Minecraft mc, LocalPlayer p) {
        if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        Inventory inv = p.getInventory();
        MultiPlayerGameMode gm = mc.gameMode;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                // Inventar-Index -> Slot im Spielermenü: Hotbar 0-8 = Slot 36-44, Rest unverändert
                int slot = i < 9 ? 36 + i : i;
                // Button 40 = Tausch mit der zweiten Hand
                gm.handleContainerInput(p.containerMenu.containerId, slot, 40, ContainerInput.SWAP, p);
                return;
            }
        }
    }
}
