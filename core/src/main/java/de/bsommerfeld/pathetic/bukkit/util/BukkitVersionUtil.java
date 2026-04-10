package de.bsommerfeld.pathetic.bukkit.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;

@UtilityClass
public class BukkitVersionUtil {

  private static final Pattern VERSION_PATTERN =
      Pattern.compile(".*\\(MC:\\s*([0-9]+\\.[0-9]+(?:\\.[0-9]+)?).*\\)");

  private static final Version LEGACY_VERSION;
  private static final CalendarVersion CALENDAR_VERSION;

  static {
    int[] parts = parseServerVersion(Bukkit.getVersion());
    if (parts[0] == 1) {
      LEGACY_VERSION = new Version(parts[1], parts[2]);
      CALENDAR_VERSION = null;
    } else {
      LEGACY_VERSION = null;
      CALENDAR_VERSION = new CalendarVersion(parts[0], parts[1], parts[2]);
    }
  }

  private static int[] parseServerVersion(String serverVersion) {
    Matcher matcher = VERSION_PATTERN.matcher(serverVersion);
    if (!matcher.matches() || matcher.group(1) == null) {
      throw new IllegalStateException("Cannot parse version string: " + serverVersion);
    }
    String[] elements = matcher.group(1).split("\\.");
    if (elements.length < 2) {
      throw new IllegalStateException("Invalid version: " + matcher.group(1));
    }
    int[] values = new int[3];
    for (int i = 0; i < Math.min(values.length, elements.length); i++) {
      values[i] = Integer.parseInt(elements[i].trim());
    }
    return values;
  }

  public static boolean isLegacyVersion() {
    return LEGACY_VERSION != null;
  }

  /** For legacy 1.X.Y Minecraft versions. */
  public static Version getVersion() {
    if (LEGACY_VERSION == null) {
      throw new IllegalStateException("Not a legacy version — use getCalendarVersion()");
    }
    return LEGACY_VERSION;
  }

  /** For calendar-versioned YY.X.Y Minecraft versions. */
  public static CalendarVersion getCalendarVersion() {
    if (CALENDAR_VERSION == null) {
      throw new IllegalStateException("Not a calendar version — use getVersion()");
    }
    return CALENDAR_VERSION;
  }

  @Getter
  public static class Version {

    private final int major;
    private final int minor;

    public Version(int major, int minor) {
      this.major = major;
      this.minor = minor;
    }

    public static Version of(int major, int minor) {
      return new Version(major, minor);
    }

    public boolean isUnder(int major, int minor) {
      if (this.major != major) return this.major < major;
      return this.minor < minor;
    }

    public boolean isUnder(Version that) {
      return isUnder(that.major, that.minor);
    }

    public boolean isOver(Version that) {
      if (this.major != that.major) return this.major > that.major;
      return this.minor > that.minor;
    }

    public boolean isEqual(Version that) {
      return this.major == that.major && this.minor == that.minor;
    }
  }

  @Getter
  public static class CalendarVersion {

    private final int year;
    private final int feature;
    private final int patch;

    public CalendarVersion(int year, int feature, int patch) {
      this.year = year;
      this.feature = feature;
      this.patch = patch;
    }

    public static CalendarVersion of(int year, int feature, int patch) {
      return new CalendarVersion(year, feature, patch);
    }

    public static CalendarVersion of(int year, int feature) {
      return new CalendarVersion(year, feature, 0);
    }

    public boolean isUnder(int year, int feature, int patch) {
      if (this.year != year) return this.year < year;
      if (this.feature != feature) return this.feature < feature;
      return this.patch < patch;
    }

    public boolean isUnder(CalendarVersion that) {
      return isUnder(that.year, that.feature, that.patch);
    }

    public boolean isOver(CalendarVersion that) {
      if (this.year != that.year) return this.year > that.year;
      if (this.feature != that.feature) return this.feature > that.feature;
      return this.patch > that.patch;
    }

    public boolean isEqual(CalendarVersion that) {
      return this.year == that.year && this.feature == that.feature && this.patch == that.patch;
    }
  }
}
