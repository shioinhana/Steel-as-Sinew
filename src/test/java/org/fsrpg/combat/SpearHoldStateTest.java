package org.fsrpg.combat;

import java.util.ArrayList;
import java.util.List;

public final class SpearHoldStateTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static AttackAim.Point point(double yaw) { return new AttackAim.Point(yaw, 0, 4.5); }
    private static AttackAim path(double... yaw) {
        var result = new ArrayList<AttackAim.Point>();
        for (double value : yaw) result.add(point(value));
        return new AttackAim(result);
    }
    public static void main(String[] args) {
        var hold = new SpearHoldState(12);
        check(hold.begin(100, 7, point(0), 0), "Accepted thrust starts a lease");
        check(!hold.begin(101, 8, point(0), 0), "Held renewal never begins or pays a second thrust");
        check(hold.held(111) && !hold.expired(111), "Last leased tick remains held");
        check(!hold.held(112) && hold.expired(112), "Lost release expires at exact server deadline");
        check(!hold.update(112, 7, path(0, 1), 0), "An expired heartbeat cannot revive an attack");
        check(hold.begin(113, 8, point(-30), 0), "New accepted swing can replace expired state");
        check(hold.update(116, 8, path(-30, 0, 30), 0), "Continuous forward path is accepted");
        check(hold.heldTicks(116) == 3, "Only server elapsed time determines held duration");
        check(!hold.release(117, 7) && hold.held(117), "Old release cannot retract the next swing");
        int before = hold.pendingPoints();
        check(!hold.update(117, 8, path(30, 70), 0), "Out-of-field path is rejected atomically");
        check(hold.pendingPoints() == before && hold.point().yaw() == 30, "Rejected aim keeps old validated point and path");
        check(!hold.update(115, 8, path(20), 0), "Older packet cannot roll server time backward");
        check(!hold.update(117, 9, path(20), 0), "Different swing cannot update an existing paid thrust");
        check(hold.update(118, 8, path(30), 0), "Stationary heartbeat extends lease");
        check(hold.pendingPoints() == before, "Heartbeat does not multiply path geometry");
        var segments = hold.drainSegments(0);
        check(segments.size() == 3, "All original adjacent directions survive draining");
        check(segments.get(1).points().equals(List.of(point(-30), point(0))), "First traversed region is preserved");
        check(segments.get(2).points().equals(List.of(point(0), point(30))), "Second traversed region is preserved");
        check(hold.pendingPoints() == 0, "Collision tick consumes pending path once");
        check(hold.drainSegments(0).getFirst().points().equals(List.of(point(30))), "Stationary extension still has collision geometry");
        check(hold.update(119, 8, path(0), 0), "The next tick connects from the previous validated point");
        check(hold.drainSegments(0).getLast().points().equals(List.of(point(30), point(0))), "Draining never loses the inter-tick bridge");
        check(hold.release(120, 8) && !hold.held(120), "Physical release ends the lease without a new attack");
        hold.cancel();
        check(hold.point() == null && hold.sequence() == -1 && hold.drainSegments(0).isEmpty(), "Interrupt completely clears geometry");

        var turning = new SpearHoldState();
        turning.begin(0, 1, point(-45), 0);
        turning.update(1, 1, path(-45, 0, 45), 0);
        var turned = turning.drainSegments(90);
        check(turned.size() == 1 && turned.getFirst().points().equals(List.of(point(45))), "Large body turn breaks the old bridge through the back");
        check(turning.drainSegments(180).isEmpty(), "Old extension cannot attack behind the new forward field");

        var bounded = new SpearHoldState();
        bounded.begin(0, 1, point(0), 0);
        var chunk = new ArrayList<AttackAim.Point>();
        for (int i = 0; i < 32; i++) chunk.add(point(i % 2 == 0 ? 0 : 1));
        var many = new AttackAim(chunk);
        int accepted = 0;
        while (bounded.update(0, 1, many, 0)) accepted++;
        check(accepted >= 7 && bounded.pendingPoints() <= SpearHoldState.MAX_PENDING_POINTS, "Burst input has a finite per-tick geometry budget");
        int count = bounded.pendingPoints();
        check(!bounded.update(1, 1, many, 0) && bounded.pendingPoints() == count, "Full queue rejects complete packets without dropping covered regions");
        check(!bounded.held(12), "Rejected flood does not extend input lease");
        bounded.drainSegments(0);
        check(bounded.update(1, 1, path(1, 0), 0), "Next collision tick releases the finite input budget");
        System.out.println("SpearHoldStateTest passed: " + checks + " checks");
    }
}
