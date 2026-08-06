package dev.rook.changes.version;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class Versions {

    private static final Comparator<String> ORDER = Versions::compare;

    private Versions() {}

    public static int compare(String left, String right) {
        List<Item> a = parse(left);
        List<Item> b = parse(right);
        int size = Math.max(a.size(), b.size());
        for (int i = 0; i < size; i++) {
            Item x = i < a.size() ? a.get(i) : padding(b.get(i));
            Item y = i < b.size() ? b.get(i) : padding(a.get(i));
            int result = x.compareTo(y);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    public static boolean isPrerelease(String version) {
        for (Item item : parse(version)) {
            if (item.qualifier != null && item.rank() < Item.RELEASE_RANK) {
                return true;
            }
        }
        return false;
    }

    public static List<String> sorted(Collection<String> versions) {
        List<String> copy = new ArrayList<>(versions);
        copy.sort(ORDER);
        return copy;
    }

    public static Optional<String> latest(Collection<String> versions, boolean includePrerelease) {
        return versions.stream().filter(v -> includePrerelease || !isPrerelease(v)).max(ORDER);
    }

    public static List<String> newerThan(
            Collection<String> versions, String known, boolean includePrerelease) {
        return sorted(versions).stream()
                .filter(v -> includePrerelease || !isPrerelease(v))
                .filter(v -> compare(v, known) > 0)
                .toList();
    }

    private static Item padding(Item other) {
        return other.qualifier == null ? Item.number(BigInteger.ZERO) : Item.qualifier("");
    }

    private static List<Item> parse(String version) {
        List<Item> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Boolean digits = null;
        String text = version.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == '+') {
                flush(items, current, digits);
                digits = null;
                continue;
            }
            boolean isDigit = Character.isDigit(c);
            if (digits != null && digits != isDigit) {
                flush(items, current, digits);
            }
            digits = isDigit;
            current.append(c);
        }
        flush(items, current, digits);
        return items;
    }

    private static void flush(List<Item> items, StringBuilder current, Boolean digits) {
        if (current.isEmpty()) {
            return;
        }
        String token = current.toString();
        current.setLength(0);
        if (Boolean.TRUE.equals(digits)) {
            items.add(Item.number(new BigInteger(token)));
        } else {
            items.add(Item.qualifier(token));
        }
    }

    private static final class Item implements Comparable<Item> {

        static final int RELEASE_RANK = 6;

        final BigInteger number;
        final String qualifier;

        private Item(BigInteger number, String qualifier) {
            this.number = number;
            this.qualifier = qualifier;
        }

        static Item number(BigInteger value) {
            return new Item(value, null);
        }

        static Item qualifier(String value) {
            return new Item(null, value);
        }

        int rank() {
            return switch (qualifier) {
                case "alpha", "a" -> 1;
                case "beta", "b" -> 2;
                case "milestone", "m" -> 3;
                case "rc", "cr", "pre", "preview" -> 4;
                case "snapshot", "dev" -> 5;
                case "", "ga", "final", "release" -> RELEASE_RANK;
                case "sp" -> 7;
                default -> 8;
            };
        }

        @Override
        public int compareTo(Item other) {
            if (number != null && other.number != null) {
                return number.compareTo(other.number);
            }
            if (number != null) {
                return other.rank() > RELEASE_RANK ? -1 : 1;
            }
            if (other.number != null) {
                return rank() > RELEASE_RANK ? 1 : -1;
            }
            int byRank = Integer.compare(rank(), other.rank());
            if (byRank != 0) {
                return byRank;
            }
            return rank() == 8 ? qualifier.compareTo(other.qualifier) : 0;
        }
    }
}
