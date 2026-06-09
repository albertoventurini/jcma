package app;

/**
 * A single-line record: its components ({@code x}, {@code y}) share the declaration line with the
 * record header. The find-references target — exercises {@code monikerAt}'s line-collision case
 * (a type ref must attribute to the record type {@code app/Coord#}, not a component field).
 */
public record Coord(int x, int y) {}
