package org.fsrpg.movement;

import java.util.Objects;
import org.fsrpg.progression.FootworkRules;
import org.fsrpg.progression.FootworkStyle;

/**
 * Pure-Java coverage of the historical movement branch: rule validation, the Flying Swan curve,
 * impulse/charged-jump math, and every server clock in {@link FootworkState}.
 */
public final class FootworkTest {
    private static int checks;
    public static void main(String[] args) {
        legacyDefaults(); ruleBounds(); flyingSwanCurve(); impulseAndJumpMath(); airDrag();
        requestDedupe(); landingReset(); schoolClocks(); combination(); jumpClocks(); miscClocks();
        System.out.println("FootworkTest passed: " + checks + " checks");
    }
    private static void legacyDefaults() {
        FootworkRules r = FootworkRules.defaults();
        equal(150, r.selectionPoints(), "one selection per 150 footwork points");
        near(3, r.rabbitDistance(), "Rabbit is a three-block dash");
        equal(8, r.rabbitInvulnerabilityTicks(), "Rabbit invulnerability is 0.4s");
        equal(12, r.rabbitCooldownTicks(), "Rabbit cooldown is 0.6s");
        near(.5, r.flyingSwanCurveLinear(), "Flying Swan uses the old 0.5x linear term");
        equal(30, r.flyingSwanCooldownTicks(), "Flying Swan cooldown is 1.5s");
        equal(8, r.flyingSwanWindowTicks(), "flash-thrust window is 0.4s");
        equal(4, r.jumpIntervalTicks(), "jump chain interval is four ticks");
        near(2, r.chargedJumpHeightMultiplier(), "charged jump doubles height");
        near(2, r.wallRange(), "wall kick ray is two blocks");
        near(4, r.targetRange(), "stomp target ray is four blocks");
        near(.02, r.targetDamageShare(), "stomp deals 2% max health");
        equal(24, r.specialCooldownTicks(), "stomp and wall kick share 1.2s");
        near(.5, r.airDragFactor(), "Light Body halves air drag loss");
        near(10, r.jumpImpulseMps(), "Light Body II adds ten metres per second");
        check(r.lightThirdRemovesCooldown(), "Light Body III removes school cooldowns");
        equal(100, r.combinationCooldownTicks(), "flash thrust cooldown is five seconds");
        near(.5, r.flashThrustDamageShare(), "flash thrust adds half pre-armor damage");
        equal(FootworkRules.defaults(), new FootworkRules(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, .5),
                "published legacy values are the configured defaults");
    }
    private static void ruleBounds() {
        valid(1, .01, 1, 0, 0, 0, 1, 1, 1, .01, .01, 0, 0, 0, 0, false, 0, 0, "minimum legacy bounds accepted");
        valid(1_000_000_000, 64, 72_000, 72_000, 100, 72_000, 72_000, 72_000, 100, 64, 64, 1, 72_000, 1, 100, true, 72_000, 1,
                "maximum legacy bounds accepted");
        invalid(0, 3, "selection interval must be positive");
        invalid(150, .009, "Rabbit distance too small");
        invalid(150, 64.01, "Rabbit distance too large");
        invalid(150, Double.NaN, "Rabbit distance must be finite");
        rejects(() -> new FootworkRules(150, Double.POSITIVE_INFINITY, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, .5),
                "infinite Rabbit distance rejected");
        rejects(() -> custom(150, 3, 0, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, .5), "Rabbit duration must be >=1 tick");
        rejects(() -> custom(150, 3, 8, -1, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, .5), "negative Rabbit cooldown rejected");
        rejects(() -> custom(150, 3, 8, 12, -0.1, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, .5), "negative Swan curve rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, -1, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, .5), "negative Swan cooldown rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 0, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, .5), "combination window must be positive");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 0, 2, 2, 4, .02, 24, .5, 10, true, 100, .5), "jump interval must be positive");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, .99, 2, 4, .02, 24, .5, 10, true, 100, .5), "height multiplier below one rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 0, 4, .02, 24, .5, 10, true, 100, .5), "zero wall range rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 0, .02, 24, .5, 10, true, 100, .5), "zero target range rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, 1.1, 24, .5, 10, true, 100, .5), "target damage share above one rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, -1, .5, 10, true, 100, .5), "negative stomp cooldown rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, -0.1, 10, true, 100, .5), "negative air drag rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, 1.1, 10, true, 100, .5), "air drag above one rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, -1, true, 100, .5), "negative jump impulse rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, -1, .5), "negative combination cooldown rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, -0.1), "negative flash share rejected");
        rejects(() -> custom(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, 1.1), "flash share above its pre-armor fraction rejected");
    }
    private static void flyingSwanCurve() {
        FootworkMath.Motion forward = FootworkMath.swan(new FootworkMath.Motion(.5, .1, .2), 1, 0, .5);
        double expected = 10 * 1.5 + Math.log(10); // x + 0.5x + ln x in m/s
        near((expected - 10) / 20, forward.x() - .5, "Flying Swan transforms the current speed along input");
        near(.1, forward.y(), "vertical velocity is untouched");
        near(.2, forward.z(), "perpendicular velocity is untouched");
        FootworkMath.Motion backward = FootworkMath.swan(new FootworkMath.Motion(-.1, .1, 0), 1, 0, .5);
        near(-.05, backward.x(), "non-positive speed becomes 0.5x");
        FootworkMath.Motion diagonal = FootworkMath.swan(new FootworkMath.Motion(.2, .3, .2), 1, 1, .5);
        double dx = 1 / Math.sqrt(2), dz = 1 / Math.sqrt(2);
        double speed = (0.2 * dx + 0.2 * dz) * 20;
        double next = speed + Math.log(speed) + .5 * speed;
        double delta = (next - speed) / 20;
        near(.2 + dx * delta, diagonal.x(), "diagonal Swan uses the projected component");
        near(.2 + dz * delta, diagonal.z(), "diagonal Swan keeps the same axis");
        near(.3, diagonal.y(), "diagonal Swan keeps vertical speed");
        rejects(() -> FootworkMath.swan(new FootworkMath.Motion(.1, 0, 0), 0, 0, .5), "missing direction rejected");
        rejects(() -> FootworkMath.swan(new FootworkMath.Motion(.1, 0, 0), Double.NaN, 0, .5), "nonfinite direction rejected");
        rejects(() -> FootworkMath.swan(new FootworkMath.Motion(.1, 0, 0), 1, 0, -1), "negative curve rejected");
        rejects(() -> new FootworkMath.Motion(Double.NaN, 0, 0), "nonfinite motion rejected");
        rejects(() -> new FootworkMath.Motion(0, 0, Double.POSITIVE_INFINITY), "nonfinite motion rejected");
    }
    private static void impulseAndJumpMath() {
        FootworkMath.Motion boosted = FootworkMath.impulse(new FootworkMath.Motion(.1, .2, .3), 1, 0, 10);
        near(.6, boosted.x(), "ten metres per second is half a block per tick");
        near(.2, boosted.y(), "impulse keeps vertical velocity");
        near(.3, boosted.z(), "impulse keeps perpendicular velocity");
        FootworkMath.Motion keep = FootworkMath.impulse(new FootworkMath.Motion(.1, .2, .3), 0, 0, 10);
        equal(new FootworkMath.Motion(.1, .2, .3), keep, "zero direction cannot invent an impulse");
        rejects(() -> FootworkMath.impulse(new FootworkMath.Motion(0, 0, 0), 0, 0, -1), "negative impulse rejected");
        rejects(() -> FootworkMath.impulse(new FootworkMath.Motion(0, 0, 0), Double.NaN, 0, 1), "nonfinite impulse direction rejected");
        near(0.42 * Math.sqrt(2), FootworkMath.chargedJumpVelocity(.42, 2), "charged jump squares the height ratio");
        near(.42, FootworkMath.chargedJumpVelocity(.42, 1), "unmodified height keeps vanilla launch");
        rejects(() -> FootworkMath.chargedJumpVelocity(-.1, 2), "negative launch velocity rejected");
        rejects(() -> FootworkMath.chargedJumpVelocity(.42, .5), "charged multiplier below one rejected");
        FootworkMath.Motion reversed = FootworkMath.wallReverse(new FootworkMath.Motion(.15, .2, -.05), .42);
        near(-.15, reversed.x(), "wall kick reverses X exactly");
        near(.42, reversed.y(), "wall kick keeps the jump velocity");
        near(.05, reversed.z(), "wall kick reverses Z exactly");
    }
    private static void airDrag() {
        near(1, FootworkMath.airDragScale(1), "factor 1 keeps vanilla drag");
        for (double factor : new double[]{0, .25, .5, 1}) {
            near(1 - .09 * factor, .91 * FootworkMath.airDragScale(factor), "post-travel scale halves the loss at " + factor);
        }
        rejects(() -> FootworkMath.airDragScale(-.1), "negative drag loss rejected");
        rejects(() -> FootworkMath.airDragScale(1.1), "drag loss above vanilla rejected");
        rejects(() -> FootworkMath.airDragScale(Double.NaN), "nonfinite drag loss rejected");
    }
    private static void requestDedupe() {
        FootworkState s = new FootworkState();
        check(s.request(1), "first request accepted");
        check(!s.request(1), "duplicate request ignored");
        check(!s.request(0), "zero request id rejected");
        check(!s.request(-5), "negative request id rejected");
        check(s.request(3), "forward request accepted");
        check(!s.request(2), "older request rejected");
        check(s.request(Long.MAX_VALUE), "largest request id accepted once");
        check(!s.request(Long.MAX_VALUE), "largest request id cannot repeat");
    }
    private static void landingReset() {
        FootworkRules r = FootworkRules.defaults();
        FootworkState s = new FootworkState();
        s.ground(true);
        check(s.grounded(), "standing player is grounded");
        s.jumpAccepted(true, 100, r);
        s.jumpAccepted(true, 104, r);
        equal(2, s.jumpsUsed(), "two air jumps used");
        s.ground(false);
        s.ground(false);
        equal(2, s.jumpsUsed(), "staying airborne does not restore jumps");
        s.ground(true);
        equal(0, s.jumpsUsed(), "real landing restores every air jump");
    }
    private static void schoolClocks() {
        FootworkRules r = FootworkRules.defaults();
        FootworkState rabbit = new FootworkState();
        check(!rabbit.styleReady(FootworkStyle.NONE, 0), "no school cannot activate");
        check(rabbit.styleReady(FootworkStyle.RABBIT, 0), "Rabbit ready initially");
        rabbit.styleAccepted(FootworkStyle.RABBIT, 0, 0, r);
        check(rabbit.invulnerable(0), "dash window opens on activation");
        check(rabbit.invulnerable(7), "last invulnerable tick");
        check(!rabbit.invulnerable(8), "window closes at its end tick");
        check(!rabbit.styleReady(FootworkStyle.RABBIT, 0), "same tick cannot reactivate");
        check(!rabbit.styleReady(FootworkStyle.RABBIT, 11), "cooldown blocks before 12 ticks");
        check(rabbit.styleReady(FootworkStyle.RABBIT, 12), "cooldown ends at 12 ticks");
        equal(12L, rabbit.rabbitRemaining(0), "remaining cooldown reported");
        equal(0L, rabbit.rabbitRemaining(12), "expired cooldown reports zero");
        FootworkState swan = new FootworkState();
        swan.styleAccepted(FootworkStyle.FLYING_SWAN, 0, 100, r);
        check(swan.combinationWindow(100), "Swan opens its combination window immediately");
        check(swan.combinationWindow(107), "Swan window lasts eight ticks");
        check(!swan.combinationWindow(108), "Swan window closes after eight ticks");
        check(!swan.styleReady(FootworkStyle.FLYING_SWAN, 129), "Swan cooldown is 30 ticks");
        check(swan.styleReady(FootworkStyle.FLYING_SWAN, 130), "Swan cooldown ends at 30 ticks");
        FootworkState light = new FootworkState();
        light.styleAccepted(FootworkStyle.RABBIT, 3, 500, r);
        check(!light.styleReady(FootworkStyle.RABBIT, 500), "Light Body III keeps the same-tick guard");
        check(light.styleReady(FootworkStyle.RABBIT, 501), "Light Body III removes the 12-tick cooldown");
        light.styleAccepted(FootworkStyle.FLYING_SWAN, 3, 502, r);
        check(light.styleReady(FootworkStyle.FLYING_SWAN, 503), "Light Body III removes the 30-tick cooldown too");
        FootworkState tiered = new FootworkState();
        tiered.styleAccepted(FootworkStyle.RABBIT, 2, 0, r);
        check(!tiered.styleReady(FootworkStyle.RABBIT, 11), "Light Body II does not remove cooldowns");
    }
    private static void combination() {
        FootworkRules r = FootworkRules.defaults();
        FootworkState s = new FootworkState();
        check(!s.combinationWindow(0), "no window before activation");
        check(!s.takeCombination(0, r), "cannot flash without a school window");
        s.styleAccepted(FootworkStyle.RABBIT, 0, 10, r);
        check(s.takeCombination(10, r), "first flash inside the window accepted");
        check(!s.takeCombination(11, r), "combination cooldown blocks an immediate second flash");
        check(!s.takeCombination(107, r), "window already closed after five seconds");
        equal(100L, s.combinationRemaining(10), "combination cooldown is 100 ticks");
        FootworkState swan = new FootworkState();
        swan.styleAccepted(FootworkStyle.FLYING_SWAN, 0, 0, r);
        check(swan.takeCombination(0, r), "Swan window also enables flash thrust");
        check(!swan.takeCombination(1, r), "Swan flash cannot repeat within its window");
    }
    private static void jumpClocks() {
        FootworkRules r = FootworkRules.defaults();
        FootworkState s = new FootworkState();
        check(s.jumpReady(0, r), "first jump is always ready");
        s.onJump(10);
        check(!s.jumpReady(13, r), "shared interval blocks the third tick after a jump");
        check(s.jumpReady(14, r), "four ticks after a jump are ready");
        check(s.airJumpReady(1, 14, r), "Cloud Step grants one air jump");
        s.jumpAccepted(true, 14, r);
        equal(1, s.jumpsUsed(), "air jump counted");
        check(!s.airJumpReady(1, 18, r), "Cloud Step exhausted after one air jump");
        check(s.airJumpReady(2, 18, r), "Cloud Step II grants the second air jump");
        s.jumpAccepted(true, 18, r);
        check(!s.airJumpReady(2, 22, r), "Cloud Step II exhausted after two air jumps");
        check(s.airJumpReady(3, 22, r), "Cloud Step III grants the third air jump");
        s.jumpAccepted(true, 22, r);
        check(!s.airJumpReady(3, 26, r), "three air jumps is the maximum for Cloud Step III");
        FootworkState high = new FootworkState();
        high.jumpAccepted(false, 50, r);
        equal(0, high.jumpsUsed(), "charged ground jump never consumes an air jump");
        FootworkState special = new FootworkState();
        special.onJump(0);
        check(!special.specialReady(3, r), "stomp obeys the shared jump interval");
        check(special.specialReady(4, r), "stomp ready once the jump interval elapsed");
        special.specialAccepted(4, r);
        check(!special.specialReady(27, r), "stomp/wall kick cooldown is 24 ticks");
        check(special.specialReady(28, r), "stomp ready again after 24 ticks");
        equal(0, special.jumpsUsed(), "stomp does not consume a normal air jump");
    }
    private static void miscClocks() {
        FootworkState s = new FootworkState();
        equal(1L, s.nextSequence(), "velocity packets are sequenced from one");
        equal(2L, s.nextSequence(), "sequence increments monotonically");
        equal(3L, s.nextSequence(), "sequence keeps incrementing");
        equal(0L, s.rabbitRemaining(0), "no Rabbit cooldown before activation");
        equal(0L, s.swanRemaining(0), "no Swan cooldown before activation");
        equal(0L, s.specialRemaining(0), "no stomp cooldown before activation");
    }
    private static FootworkRules custom(int selectionPoints, double rabbitDistance, int rabbitInvulnerabilityTicks,
            int rabbitCooldownTicks, double flyingSwanCurveLinear, int flyingSwanCooldownTicks,
            int flyingSwanWindowTicks, int jumpIntervalTicks, double chargedJumpHeightMultiplier,
            double wallRange, double targetRange, double targetDamageShare, int specialCooldownTicks,
            double airDragFactor, double jumpImpulseMps, boolean lightThirdRemovesCooldown,
            int combinationCooldownTicks, double flashThrustDamageShare) {
        return new FootworkRules(selectionPoints, rabbitDistance, rabbitInvulnerabilityTicks, rabbitCooldownTicks,
                flyingSwanCurveLinear, flyingSwanCooldownTicks, flyingSwanWindowTicks, jumpIntervalTicks,
                chargedJumpHeightMultiplier, wallRange, targetRange, targetDamageShare, specialCooldownTicks,
                airDragFactor, jumpImpulseMps, lightThirdRemovesCooldown, combinationCooldownTicks, flashThrustDamageShare);
    }
    private static void valid(int selectionPoints, double rabbitDistance, int invulnerability, int rabbitCooldown,
            double curve, int swanCooldown, int swanWindow, int jumpInterval, double charged, double wall, double target,
            double targetShare, int specialCooldown, double drag, double impulse, boolean light3, int combinationCooldown,
            double flashShare, String message) {
        checks++;
        custom(selectionPoints, rabbitDistance, invulnerability, rabbitCooldown, curve, swanCooldown, swanWindow,
                jumpInterval, charged, wall, target, targetShare, specialCooldown, drag, impulse, light3,
                combinationCooldown, flashShare);
    }
    private static void invalid(int selectionPoints, double rabbitDistance, String message) {
        rejects(() -> custom(selectionPoints, rabbitDistance, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24, .5, 10, true, 100, .5), message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + " expected=" + expected + " actual=" + actual);
    }
    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1e-8, message + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable call, String message) {
        checks++;
        try { call.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
}
