package org.fsrpg.combat;

import java.util.*;

/** Actual melee contacts, ordered by server time rather than actor iteration order. */
public final class WeaponClash<T> {
    public record Contact<T>(UUID attacker, UUID victim, long sequence, long tick, double damage, T value) {
        public Contact {
            Objects.requireNonNull(attacker); Objects.requireNonNull(victim); Objects.requireNonNull(value);
            if (attacker.equals(victim) || sequence < 0 || tick < 0 || !Double.isFinite(damage) || damage < 0)
                throw new IllegalArgumentException("invalid melee contact");
        }
    }
    public record Exchange(double firstDamage, double secondDamage) {
        public int winner() { return firstDamage > 0 ? 1 : secondDamage > 0 ? -1 : 0; }
    }
    public record Pair<T>(Contact<T> first, Contact<T> second, Exchange exchange) {}
    public record Batch<T>(List<Pair<T>> pairs, List<Contact<T>> expired) {}
    private final List<Contact<T>> pending = new ArrayList<>();
    private final java.util.function.BiPredicate<T,T> compatible;
    public WeaponClash() { this((a,b)->true); }
    public WeaponClash(java.util.function.BiPredicate<T,T> compatible) { this.compatible=Objects.requireNonNull(compatible); }

    public static Exchange compare(double first, double second, double tolerance) {
        if (!Double.isFinite(first) || !Double.isFinite(second) || !Double.isFinite(tolerance)
                || first < 0 || second < 0 || tolerance < 0) throw new IllegalArgumentException("invalid clash damage");
        double delta = first - second;
        // Decimal-authored damage may straddle the tolerance by a few binary rounding units.
        double rounding = Math.ulp(first) + Math.ulp(second) + Math.ulp(tolerance);
        if (tolerance > 0 && Math.abs(delta) - tolerance <= rounding) return new Exchange(0, 0);
        return new Exchange(Math.max(0, delta - tolerance), Math.max(0, -delta - tolerance));
    }
    /** One swing may claim a particular target only once, even during a held spear sweep. */
    public boolean offer(Contact<T> contact) {
        if (pending.stream().anyMatch(c -> c.attacker().equals(contact.attacker())
                && c.victim().equals(contact.victim()) && c.sequence() == contact.sequence())) return false;
        pending.add(contact); return true;
    }
    /** Call after every source has supplied this tick's contacts. The two-tick boundary is inclusive. */
    public Batch<T> resolve(long now, WeaponClashRules rules) {
        pending.sort(Comparator.<Contact<T>>comparingLong(Contact::tick)
                .thenComparing(c -> c.attacker().toString()).thenComparing(c -> c.victim().toString())
                .thenComparingLong(Contact::sequence));
        List<Pair<T>> pairs = new ArrayList<>();
        Set<Contact<T>> used = new HashSet<>();
        for (int i = 0; i < pending.size(); i++) {
            Contact<T> a = pending.get(i); if (used.contains(a)) continue;
            for (int j = i + 1; j < pending.size(); j++) {
                Contact<T> b = pending.get(j);
                if (used.contains(b) || b.tick() - a.tick() > rules.contactWindowTicks()) continue;
                if (a.attacker().equals(b.victim()) && a.victim().equals(b.attacker()) && compatible.test(a.value(),b.value())) {
                    pairs.add(new Pair<>(a, b, compare(a.damage(), b.damage(), rules.damageTolerance())));
                    // A stopped weapon cannot also hit a bystander in this collision batch.
                    for (Contact<T> c : pending)
                        if (sameSwing(a,c) || sameSwing(b,c)) used.add(c);
                    break;
                }
            }
        }
        List<Contact<T>> expired = new ArrayList<>();
        for (Contact<T> c : pending) if (!used.contains(c) && now - c.tick() >= rules.contactWindowTicks()) expired.add(c);
        pending.removeIf(c -> used.contains(c) || now - c.tick() >= rules.contactWindowTicks());
        return new Batch<>(List.copyOf(pairs), List.copyOf(expired));
    }
    private boolean sameSwing(Contact<T> a, Contact<T> b) { return a.attacker().equals(b.attacker()) && a.sequence()==b.sequence(); }
    public List<Contact<T>> contacts() { return List.copyOf(pending); }
    public boolean remove(Contact<T> contact) { return pending.remove(Objects.requireNonNull(contact)); }
    public void removeSwing(UUID attacker,long sequence) { pending.removeIf(c->c.attacker().equals(attacker)&&c.sequence()==sequence); }
    public void remove(UUID entity) { pending.removeIf(c -> c.attacker().equals(entity) || c.victim().equals(entity)); }
    public void clear() { pending.clear(); }
    public int size() { return pending.size(); }
}
